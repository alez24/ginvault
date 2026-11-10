package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Aggregate root: un'edizione del gin, nata da una formulazione SCELTA di Ricette.
 *
 * Della formulazione conserva il RIFERIMENTO (id + versione) e un solo dato:
 * i grammi di botanica di stagione per bottiglia, che servono per la tiratura.
 * Perché copiarlo:
 *  - è stabile: in Ricette una versione non si modifica mai, quindi la copia
 *    non può diventare vecchia;
 *  - rende Edizioni autonomo: il ricalcolo funziona anche con Ricette spento.
 *
 * Regole:
 *  - tiratura = min(600, grammi disponibili / grammi per bottiglia), tra 1 e 600;
 *  - una quota per canale, e la somma delle quote è sempre uguale alla tiratura;
 *  - se la tiratura scende si toglie prima da ONLINE, poi da BAR, mai da SOCI,
 *    e nessuna quota scende sotto le bottiglie già prenotate;
 *  - se la tiratura sale, le bottiglie in più vanno tutte a ONLINE, fino al
 *    tetto di 600;
 *  - quando la tiratura scende l'aggregato registra TiraturaRidotta, quando
 *    sale TiraturaAumentata;
 *  - la resa ricalcolata da Produzione (applicaResa) segue le stesse regole.
 *    Se non si può applicare, l'edizione resta com'era e registra
 *    RicalcoloRifiutato con la spiegazione.
 *
 * Origine della botanica di stagione (es. "Alta Val Varaita (Alpi Cozie)"):
 * la sceglie il distillatore e l'edizione la promette a soci e clienti.
 * Produzione la riceve con EdizioneCreata e accetta per il piano solo lotti
 * con la stessa origine: l'edizione resta fedele alla sua origine.
 *
 * Quando nasce, l'edizione registra l'evento EdizioneCreata: va su Kafka
 * (edizioni.eventi) attraverso la tabella outbox, nella stessa transazione
 * del salvataggio.
 *
 * Rete di sicurezza: vincolo UNIQUE su (formulazione_id, formulazione_versione).
 * Anche se Ricette sbagliasse (o il suo database venisse ricreato), nel
 * database di Edizioni la stessa formulazione non può finire in due edizioni.
 */
@Entity
@Table(name = "edizione", uniqueConstraints = @UniqueConstraint(
        name = "uk_edizione_formulazione", columnNames = {"formulazione_id", "formulazione_versione"}))
public class Edizione {

    /** Da dove si tolgono le bottiglie quando la tiratura scende, in ordine. SOCI non c'è: non si riduce mai. */
    private static final List<Canale> ORDINE_DI_RIDUZIONE = List.of(Canale.ONLINE, Canale.BAR);

    /** Dove vanno le bottiglie in più quando la tiratura sale. */
    private static final Canale CANALE_DEGLI_AUMENTI = Canale.ONLINE;

    /** La causa negli eventi di un ricalcolo chiesto a mano (POST /edizioni/{id}/ricalcolo). */
    public static final String RICALCOLO_MANUALE = "ricalcolo manuale del distillatore";

    @Id
    private UUID id;

    @Embedded
    private RiferimentoFormulazione formulazione;

    @Column(name = "botanica_di_stagione", nullable = false, length = 50)
    private String botanicaDiStagione;

    @Column(name = "grammi_per_bottiglia", nullable = false, precision = 7, scale = 2)
    private BigDecimal grammiPerBottiglia;

    @Column(name = "botanica_disponibile_kg", nullable = false, precision = 8, scale = 3)
    private BigDecimal botanicaDisponibileKg;

    /**
     * Obbligatoria per ogni edizione nuova (la controlla nuova()). Nel database
     * può essere vuota solo per le edizioni create prima che esistesse:
     * ddl-auto non può aggiungere una colonna NOT NULL a una tabella con righe.
     */
    @Column(name = "origine_botanica", length = 100)
    private String origineBotanica;

    @Embedded
    private Tiratura tiratura;

    /** Una riga per canale nella tabella quota: (edizione_id, canale, bottiglie, prenotate). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quota", joinColumns = @JoinColumn(name = "edizione_id", nullable = false))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "canale", length = 10)
    private Map<Canale, Quota> quote = new EnumMap<>(Canale.class);

    @Column(name = "creata_il", nullable = false, updatable = false)
    private Instant creataIl;

    /** Blocco ottimistico; null = edizione nuova, mai salvata. */
    @Version
    private Long revisione;

    /** Eventi di dominio in attesa di pubblicazione: non vanno nel database. */
    @Transient
    private final List<Object> eventi = new ArrayList<>();

    protected Edizione() {
        // per JPA
    }

    private Edizione(RiferimentoFormulazione formulazione, String botanicaDiStagione, BigDecimal grammiPerBottiglia,
                     BigDecimal botanicaDisponibileKg, String origineBotanica, Tiratura tiratura,
                     Map<Canale, Integer> bottiglie) {
        this.id = UUID.randomUUID();
        this.formulazione = formulazione;
        this.botanicaDiStagione = botanicaDiStagione;
        this.grammiPerBottiglia = grammiPerBottiglia;
        this.botanicaDisponibileKg = botanicaDisponibileKg;
        this.origineBotanica = origineBotanica;
        this.tiratura = tiratura;
        bottiglie.forEach((canale, numero) -> this.quote.put(canale, Quota.di(numero)));
        this.creataIl = Instant.now();
    }

    /**
     * Factory: crea l'edizione IN MEMORIA, controllando tutte le regole.
     * Va chiamata PRIMA di segnare la formulazione come usata in Ricette: se le
     * regole dell'edizione non tornano, la formulazione non viene "bruciata".
     *
     * @param formulazione      la formulazione letta da Ricette (tramite CatalogoRicette)
     * @param versioneRichiesta la versione indicata dal distillatore: deve essere quella scelta
     * @param origineBotanica   da dove deve venire la botanica di stagione
     * @param quote             bottiglie per canale: la somma deve essere uguale alla tiratura
     */
    public static Edizione nuova(FormulazioneInCatalogo formulazione, int versioneRichiesta,
                                 BigDecimal botanicaDisponibileKg, String origineBotanica,
                                 Map<Canale, Integer> quote) {
        RiferimentoFormulazione riferimento = formulazione.riferimentoPer(versioneRichiesta);
        String botanica = formulazione.botanicaDiStagione().trim().toLowerCase(Locale.ROOT);
        BigDecimal grammi = formulazione.grammiPerBottiglia().setScale(2, RoundingMode.HALF_UP);
        BigDecimal kg = verificaKg(botanicaDisponibileKg);
        String origine = verificaOrigine(origineBotanica);
        Tiratura tiratura = Tiratura.da(kg, grammi);
        Map<Canale, Integer> bottiglie = verificaQuote(quote, tiratura, kg, botanica, grammi);
        Edizione edizione = new Edizione(riferimento, botanica, grammi, kg, origine, tiratura, bottiglie);
        edizione.eventi.add(new EdizioneCreata(edizione.id, riferimento, botanica, grammi, origine, kg,
                tiratura.bottiglie(), edizione.bottigliePerCanale(), edizione.creataIl));
        return edizione;
    }

    // ---------------------------------------------------------------- comportamento

    /**
     * Nuova disponibilità di botanica (es. la lavanda nuova arriva scarsa):
     * ricalcola la tiratura e ripartisce la differenza sulle quote.
     * Tutto o niente: se una regola non torna, l'edizione resta com'era.
     *
     * @throws TiraturaNonRiducibile bisognerebbe toccare SOCI o scendere sotto le prenotate (-> 409)
     * @throws EdizioneNonValida     kg non validi o insufficienti per una bottiglia (-> 422)
     */
    public void ricalcola(BigDecimal nuoviKgDisponibili) {
        ricalcola(nuoviKgDisponibili, RICALCOLO_MANUALE, null);
    }

    /**
     * La resa ricalcolata da Produzione (evento ResaRicalcolata): stesse regole
     * di ricalcola. Un ricalcolo impossibile qui non è un errore di chi chiama,
     * è un fatto da far sapere: l'edizione resta com'era e registra
     * RicalcoloRifiutato con la spiegazione, che parte su Kafka come gli altri
     * eventi. Il messaggio di Produzione così non si perde.
     *
     * @return com'è andata: RIDOTTA, AUMENTATA, INVARIATA o RIFIUTATO
     */
    public EsitoRicalcolo applicaResa(ResaDiProduzione resa) {
        if (resa == null || resa.kgDisponibili() == null || resa.kgDisponibili().signum() < 0) {
            throw new IllegalArgumentException("Resa senza kg disponibili validi: " + resa);
        }
        int prima = tiratura.bottiglie();
        try {
            // Prima Tiratura.da: con 0 kg spiega "non si riempie nemmeno una bottiglia"
            Tiratura.da(resa.kgDisponibili(), grammiPerBottiglia);
            ricalcola(resa.kgDisponibili(), resa.motivo(), resa.eventoId());
        } catch (TiraturaNonRiducibile rifiuto) {
            return rifiuta(resa, RicalcoloRifiutato.Motivo.QUOTE_NON_RIDUCIBILI, rifiuto.getMessage());
        } catch (EdizioneNonValida rifiuto) {
            return rifiuta(resa, RicalcoloRifiutato.Motivo.BOTANICA_INSUFFICIENTE,
                    rifiuto.getMessage() + ". L'edizione non è stata modificata.");
        }
        int dopo = tiratura.bottiglie();
        EsitoRicalcolo.Tipo tipo = dopo < prima ? EsitoRicalcolo.Tipo.RIDOTTA
                : dopo > prima ? EsitoRicalcolo.Tipo.AUMENTATA : EsitoRicalcolo.Tipo.INVARIATA;
        return new EsitoRicalcolo(tipo, prima, dopo, null);
    }

    private EsitoRicalcolo rifiuta(ResaDiProduzione resa, RicalcoloRifiutato.Motivo motivo, String spiegazione) {
        int bottiglie = tiratura.bottiglie();
        eventi.add(new RicalcoloRifiutato(id, motivo, bottiglie,
                Tiratura.bottigliePossibili(resa.kgDisponibili(), grammiPerBottiglia), botanicaDisponibileKg,
                resa.kgDisponibili(), quote(), spiegazione, resa.motivo(), resa.eventoId(), Instant.now()));
        return new EsitoRicalcolo(EsitoRicalcolo.Tipo.RIFIUTATO, bottiglie, bottiglie, spiegazione);
    }

    /** Il ricalcolo vero, manuale o da una resa: causa e resaRicalcolataId finiscono negli eventi. */
    private void ricalcola(BigDecimal nuoviKgDisponibili, String causa, UUID resaRicalcolataId) {
        BigDecimal kg = verificaKg(nuoviKgDisponibili);
        Tiratura vecchia = tiratura;
        Tiratura nuova = Tiratura.da(kg, grammiPerBottiglia);

        Map<Canale, Quota> ripartite;
        if (nuova.minoreDi(vecchia)) {
            ripartite = riduci(vecchia, nuova, kg);
        } else if (vecchia.minoreDi(nuova)) {
            ripartite = aumenta(nuova.bottiglie() - vecchia.bottiglie());
        } else {
            ripartite = quote();   // stessa tiratura (es. 12 kg -> 13 kg, sempre 600): cambiano solo i kg
        }

        // Da qui nessuna eccezione: lo stato cambia tutto insieme
        botanicaDisponibileKg = kg;
        tiratura = nuova;
        quote.putAll(ripartite);
        if (nuova.minoreDi(vecchia)) {
            eventi.add(new TiraturaRidotta(id, vecchia.bottiglie(), nuova.bottiglie(), bottigliePerCanale(), kg,
                    causa, resaRicalcolataId, Instant.now()));
        } else if (vecchia.minoreDi(nuova)) {
            eventi.add(new TiraturaAumentata(id, vecchia.bottiglie(), nuova.bottiglie(), bottigliePerCanale(), kg,
                    causa, resaRicalcolataId, Instant.now()));
        }
    }

    /**
     * Prenota bottiglie di un canale. Oggi la chiamano solo i test: l'endpoint
     * delle prenotazioni arriverà con la storia delle prenotazioni.
     *
     * @throws QuotaEsaurita se nel canale non ci sono abbastanza bottiglie libere (-> 409)
     */
    public void prenota(Canale canale, int bottiglie) {
        if (bottiglie < 1) {
            throw new EdizioneNonValida("Si prenota almeno una bottiglia");
        }
        Quota quota = quote.get(canale);
        if (bottiglie > quota.libere()) {
            throw new QuotaEsaurita("Nel canale " + canale + " restano " + quota.libere()
                    + " bottiglie libere: non se ne possono prenotare " + bottiglie);
        }
        quote.put(canale, quota.conPrenotate(quota.prenotate() + bottiglie));
    }

    // ---------------------------------------------------------------- eventi di dominio

    /**
     * Gli eventi registrati e non ancora pubblicati. Spring Data li legge
     * quando si chiama repository.save(edizione) e li pubblica come eventi
     * dell'applicazione; poi chiama eventiPubblicati().
     */
    @DomainEvents
    public List<Object> eventi() {
        return List.copyOf(eventi);
    }

    @AfterDomainEventPublication
    protected void eventiPubblicati() {
        eventi.clear();
    }

    // ---------------------------------------------------------------- lettura

    public UUID id() {
        return id;
    }

    public RiferimentoFormulazione formulazione() {
        return formulazione;
    }

    public String botanicaDiStagione() {
        return botanicaDiStagione;
    }

    public BigDecimal grammiPerBottiglia() {
        return grammiPerBottiglia;
    }

    public BigDecimal botanicaDisponibileKg() {
        return botanicaDisponibileKg;
    }

    /** null solo per le edizioni create prima che l'origine esistesse. */
    public String origineBotanica() {
        return origineBotanica;
    }

    public Tiratura tiratura() {
        return tiratura;
    }

    /** Le quote in ordine di canale (SOCI, BAR, ONLINE), non modificabili. */
    public Map<Canale, Quota> quote() {
        EnumMap<Canale, Quota> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        return Collections.unmodifiableMap(copia);
    }

    /** Solo le bottiglie per canale (SOCI, BAR, ONLINE). */
    public Map<Canale, Integer> bottigliePerCanale() {
        EnumMap<Canale, Integer> bottiglie = new EnumMap<>(Canale.class);
        quote.forEach((canale, quota) -> bottiglie.put(canale, quota.bottiglie()));
        return Collections.unmodifiableMap(bottiglie);
    }

    public Instant creataIl() {
        return creataIl;
    }

    // ---------------------------------------------------------------- regole

    /** Tiratura scesa: si toglie da ONLINE, poi da BAR, mai sotto le prenotate e mai da SOCI. */
    private Map<Canale, Quota> riduci(Tiratura vecchia, Tiratura nuova, BigDecimal kg) {
        int daTogliere = vecchia.bottiglie() - nuova.bottiglie();
        EnumMap<Canale, Quota> ripartite = new EnumMap<>(quote());
        int restano = daTogliere;
        for (Canale canale : ORDINE_DI_RIDUZIONE) {
            Quota quota = ripartite.get(canale);
            int tolte = Math.min(restano, quota.libere());
            ripartite.put(canale, quota.conBottiglie(quota.bottiglie() - tolte));
            restano -= tolte;
        }
        if (restano > 0) {
            throw new TiraturaNonRiducibile(perchéNonSiRiduce(vecchia, nuova, kg, daTogliere));
        }
        return ripartite;
    }

    private String perchéNonSiRiduce(Tiratura vecchia, Tiratura nuova, BigDecimal kg, int daTogliere) {
        String premessa = "Con " + kg.stripTrailingZeros().toPlainString() + " kg di " + botanicaDiStagione
                + " la tiratura scenderebbe da " + vecchia.bottiglie() + " a " + nuova.bottiglie()
                + " bottiglie: ne vanno tolte " + daTogliere;
        int riducibili = ORDINE_DI_RIDUZIONE.stream().mapToInt(canale -> quote.get(canale).bottiglie()).sum();
        if (daTogliere > riducibili) {
            return premessa + ", ma ONLINE e BAR ne hanno in tutto " + riducibili + " e la quota SOCI ("
                    + quote.get(Canale.SOCI).bottiglie() + ") non si riduce mai. L'edizione non è stata modificata.";
        }
        int libere = ORDINE_DI_RIDUZIONE.stream().mapToInt(canale -> quote.get(canale).libere()).sum();
        Quota online = quote.get(Canale.ONLINE);
        Quota bar = quote.get(Canale.BAR);
        return premessa + ", ma ONLINE e BAR ne hanno solo " + libere + " non prenotate (ONLINE "
                + online.prenotate() + " prenotate su " + online.bottiglie() + ", BAR " + bar.prenotate()
                + " su " + bar.bottiglie() + "): nessuna quota può scendere sotto le bottiglie già prenotate."
                + " L'edizione non è stata modificata.";
    }

    /** Tiratura salita: tutte le bottiglie in più vanno a ONLINE (Tiratura.da ha già il tetto di 600). */
    private Map<Canale, Quota> aumenta(int inPiù) {
        EnumMap<Canale, Quota> ripartite = new EnumMap<>(quote());
        Quota quota = ripartite.get(CANALE_DEGLI_AUMENTI);
        ripartite.put(CANALE_DEGLI_AUMENTI, quota.conBottiglie(quota.bottiglie() + inPiù));
        return ripartite;
    }

    private static BigDecimal verificaKg(BigDecimal kg) {
        if (kg == null || kg.signum() <= 0) {
            throw new EdizioneNonValida("I kg di botanica disponibili devono essere maggiori di zero");
        }
        if (kg.stripTrailingZeros().scale() > 3) {
            throw new EdizioneNonValida("I kg di botanica si indicano al grammo (al massimo 3 decimali)");
        }
        return kg.setScale(3, RoundingMode.UNNECESSARY);
    }

    /** Obbligatoria, al massimo 100 caratteri; spazi doppi ridotti a uno. */
    private static String verificaOrigine(String origine) {
        if (origine == null || origine.isBlank()) {
            throw new EdizioneNonValida("Serve l'origine della botanica di stagione (es. Alta Val Varaita (Alpi Cozie)):"
                    + " Produzione accetterà solo lotti con questa origine");
        }
        String pulita = origine.trim().replaceAll("\\s+", " ");
        if (pulita.length() > 100) {
            throw new EdizioneNonValida("L'origine della botanica può avere al massimo 100 caratteri");
        }
        return pulita;
    }

    private static Map<Canale, Integer> verificaQuote(Map<Canale, Integer> quote, Tiratura tiratura, BigDecimal kg,
                                                      String botanica, BigDecimal grammi) {
        if (quote == null) {
            throw new EdizioneNonValida("Servono le quote per canale");
        }
        EnumMap<Canale, Integer> verificate = new EnumMap<>(Canale.class);
        long somma = 0;
        for (Canale canale : Canale.values()) {
            Integer bottiglie = quote.get(canale);
            if (bottiglie == null) {
                throw new EdizioneNonValida("Serve una quota per ogni canale: manca " + canale);
            }
            if (bottiglie < 0) {
                throw new EdizioneNonValida("La quota " + canale + " non può essere negativa");
            }
            verificate.put(canale, bottiglie);
            somma += bottiglie;
        }
        if (somma != tiratura.bottiglie()) {
            throw new EdizioneNonValida("La somma delle quote (" + somma + ") deve essere uguale alla tiratura: "
                    + tiratura.bottiglie() + " bottiglie (" + kg.stripTrailingZeros().toPlainString() + " kg di "
                    + botanica + " a " + grammi.stripTrailingZeros().toPlainString() + " g per bottiglia, al massimo "
                    + Tiratura.MASSIMA + ")");
        }
        return verificate;
    }
}
