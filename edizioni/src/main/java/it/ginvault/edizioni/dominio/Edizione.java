package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

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

import org.hibernate.annotations.ColumnDefault;
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
 *
 * Vendita (storia di Giulia):
 *  - l'edizione nasce IN_PRELAZIONE, per la durata decisa dal Club (7 giorni):
 *    prenotano solo i soci, dalla quota SOCI, al massimo 2 bottiglie a testa;
 *  - dopo, o quando il distillatore apre la vendita in anticipo, soci e
 *    clienti prenotano dalla quota ONLINE, al massimo 6 bottiglie a testa;
 *  - una prenotazione toglie subito le bottiglie dalla quota (prenotate), e
 *    nessuna quota ha più prenotate che bottiglie;
 *  - lo stato (StatoEdizione) non si salva: si calcola dall'orologio e dalle quote.
 * Dell'annuncio restano tiratura, kg e quote annunciati: dopo un raccolto
 * scarso i soci vedono "da 600 a 540".
 *
 * La SAGA del pagamento (pagamentoRiuscito, pagamentoFallito, scadenza) passa
 * da qui perché tocca le quote e i numeri di bottiglia:
 *  - pagamento riuscito: la prenotazione è confermata e prende i numeri
 *    successivi dell'edizione (dopo 111 bottiglie numerate, Giulia ha 112 e 113);
 *  - pagamento rifiutato o tempo finito: la prenotazione è annullata e le sue
 *    bottiglie tornano libere nella quota (compensazione);
 *  - pagamento riuscito a prenotazione già annullata: si chiede il rimborso.
 * Chi chiama tiene bloccata la riga dell'edizione (findPerModifica) fino al
 * commit: così i numeri non hanno né buchi né doppioni anche con conferme
 * nello stesso istante. Rete di sicurezza: UNIQUE (edizione_id, numero) nella
 * tabella bottiglia_numerata.
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

    /** Date e ore nei messaggi per soci e clienti: "15 luglio 2026 alle 10:24", ora di Roma. */
    private static final DateTimeFormatter DATA_E_ORA = DateTimeFormatter
            .ofPattern("d MMMM yyyy 'alle' HH:mm", Locale.ITALIAN).withZone(ZoneId.of("Europe/Rome"));

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

    /**
     * Fino a quando prenotano solo i soci. Obbligatoria per ogni edizione nuova;
     * vuota solo per quelle create prima della prelazione, che sono già in vendita
     * (ddl-auto non può aggiungere una colonna NOT NULL a una tabella con righe).
     */
    @Column(name = "prelazione_fino_al")
    private Instant prelazioneFinoAl;

    /** La tiratura dell'annuncio, che i ricalcoli non toccano; vuota per le edizioni di prima. */
    @Column(name = "tiratura_annunciata")
    private Integer tiraturaAnnunciata;

    /** I kg di botanica dell'annuncio; vuoti per le edizioni di prima. */
    @Column(name = "botanica_annunciata_kg", precision = 8, scale = 3)
    private BigDecimal botanicaAnnunciataKg;

    /** Le bottiglie per canale dell'annuncio, nella tabella quota_annunciata; nessuna riga per le edizioni di prima. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quota_annunciata", joinColumns = @JoinColumn(name = "edizione_id", nullable = false))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "canale", length = 10)
    @Column(name = "bottiglie", nullable = false)
    private Map<Canale, Integer> quoteAnnunciate = new EnumMap<>(Canale.class);

    /**
     * Quante bottiglie hanno già un numero: la prossima prenotazione confermata
     * prende i numeri successivi. Default 0 nel database: così ddl-auto può
     * aggiungere la colonna NOT NULL anche a una tabella che ha già righe.
     */
    @ColumnDefault("0")
    @Column(name = "bottiglie_numerate", nullable = false)
    private int bottiglieNumerate;

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
                     Map<Canale, Integer> bottiglie, Duration durataPrelazione) {
        this.id = UUID.randomUUID();
        this.formulazione = formulazione;
        this.botanicaDiStagione = botanicaDiStagione;
        this.grammiPerBottiglia = grammiPerBottiglia;
        this.botanicaDisponibileKg = botanicaDisponibileKg;
        this.origineBotanica = origineBotanica;
        this.tiratura = tiratura;
        bottiglie.forEach((canale, numero) -> this.quote.put(canale, Quota.di(numero)));
        this.creataIl = Instant.now();
        this.prelazioneFinoAl = creataIl.plus(durataPrelazione);
        this.tiraturaAnnunciata = tiratura.bottiglie();
        this.botanicaAnnunciataKg = botanicaDisponibileKg;
        this.quoteAnnunciate.putAll(bottiglie);
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
     * @param durataPrelazione  quanto dura la prelazione dei soci (la decide il Club)
     */
    public static Edizione nuova(FormulazioneInCatalogo formulazione, int versioneRichiesta,
                                 BigDecimal botanicaDisponibileKg, String origineBotanica,
                                 Map<Canale, Integer> quote, Duration durataPrelazione) {
        RiferimentoFormulazione riferimento = formulazione.riferimentoPer(versioneRichiesta);
        String botanica = formulazione.botanicaDiStagione().trim().toLowerCase(Locale.ROOT);
        BigDecimal grammi = formulazione.grammiPerBottiglia().setScale(2, RoundingMode.HALF_UP);
        BigDecimal kg = verificaKg(botanicaDisponibileKg);
        String origine = verificaOrigine(origineBotanica);
        Tiratura tiratura = Tiratura.da(kg, grammi);
        Map<Canale, Integer> bottiglie = verificaQuote(quote, tiratura, kg, botanica, grammi);
        if (durataPrelazione == null || durataPrelazione.isNegative()) {
            throw new IllegalArgumentException("Durata della prelazione non valida: " + durataPrelazione);
        }
        Edizione edizione = new Edizione(riferimento, botanica, grammi, kg, origine, tiratura, bottiglie,
                durataPrelazione);
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

    // ---------------------------------------------------------------- vendita

    /**
     * In che fase di vendita è l'edizione adesso: in prelazione fino alla sua
     * fine, poi in vendita finché ONLINE ha bottiglie libere, poi esaurita.
     */
    public StatoEdizione stato(Instant adesso) {
        if (inPrelazione(adesso)) {
            return StatoEdizione.IN_PRELAZIONE;
        }
        return quote.get(Canale.ONLINE).libere() > 0 ? StatoEdizione.IN_VENDITA : StatoEdizione.ESAURITA;
    }

    /**
     * Il distillatore chiude la prelazione in anticipo: da adesso prenotano
     * soci e clienti, dalla quota ONLINE. Se la prelazione è già finita non
     * cambia niente.
     */
    public void apriVendita(Instant adesso) {
        if (inPrelazione(adesso)) {
            prelazioneFinoAl = adesso;
        }
    }

    /**
     * Una persona prenota bottiglie dell'edizione. In prelazione solo i soci,
     * dalla quota SOCI; dopo tutti, dalla quota ONLINE. Le bottiglie escono
     * subito dalla quota: la prenotazione nasce in attesa di pagamento e va
     * salvata insieme all'edizione, nella stessa transazione.
     *
     * Il limite per persona conta le prenotazioni non annullate dello stesso
     * canale: chi ha già 2 bottiglie in prelazione non ne aggiunge altre.
     * Perché il conto sia giusto anche con due richieste nello stesso istante,
     * chi chiama legge l'edizione con il blocco (EdizioneRepository.findPerModifica).
     *
     * @param sueAltre le prenotazioni che la persona ha già per questa edizione
     * @throws PrenotazioneNonPossibile in prelazione e non socio, o oltre il limite per persona (-> 409)
     * @throws QuotaEsaurita            nella quota non ci sono abbastanza bottiglie libere (-> 409)
     * @throws EdizioneNonValida        meno di una bottiglia (-> 422)
     */
    public Prenotazione prenota(Acquirente acquirente, int bottiglie, List<Prenotazione> sueAltre,
                                RegoleDiVendita regole, Instant adesso) {
        if (bottiglie < 1) {
            throw new EdizioneNonValida("Si prenota almeno una bottiglia");
        }
        boolean prelazione = inPrelazione(adesso);
        if (prelazione && !acquirente.socio()) {
            throw new PrenotazioneNonPossibile(PrenotazioneNonPossibile.Motivo.PRELAZIONE_DEI_SOCI,
                    "Fino al " + DATA_E_ORA.format(prelazioneFinoAl) + " l'edizione è in prelazione: la prenotano"
                            + " solo i soci del club. Dopo si apre la vendita online.");
        }
        Canale canale = prelazione ? Canale.SOCI : Canale.ONLINE;
        int massimo = prelazione ? regole.bottigliePerSocio() : regole.bottigliePerCliente();
        int giàPrenotate = sueAltre.stream()
                .filter(altra -> altra.edizioneId().equals(id) && altra.canale() == canale && altra.occupaBottiglie())
                .mapToInt(Prenotazione::bottiglie)
                .sum();
        if (giàPrenotate + bottiglie > massimo) {
            throw new PrenotazioneNonPossibile(PrenotazioneNonPossibile.Motivo.LIMITE_PER_PERSONA,
                    oltreIlLimite(prelazione, massimo, giàPrenotate, bottiglie));
        }
        occupa(canale, bottiglie);
        BigDecimal prezzo = prelazione ? regole.prezzoSoci() : regole.prezzoOnline();
        return Prenotazione.nuova(id, acquirente.id(), canale, bottiglie, prezzo, adesso,
                adesso.plus(regole.tempoPerPagare()));
    }

    /**
     * Toglie bottiglie libere da una quota, con la sola regola della quota.
     * Le prenotazioni di soci e clienti passano da prenota(), che controlla
     * anche prelazione e limiti; questo metodo resta visibile solo nel
     * pacchetto (e ai test).
     *
     * @throws QuotaEsaurita se nel canale non ci sono abbastanza bottiglie libere (-> 409)
     */
    void occupa(Canale canale, int bottiglie) {
        if (bottiglie < 1) {
            throw new EdizioneNonValida("Si prenota almeno una bottiglia");
        }
        Quota quota = quote.get(canale);
        if (bottiglie > quota.libere()) {
            String restano = quota.libere() == 1 ? "resta 1 bottiglia libera" : "restano " + quota.libere()
                    + " bottiglie libere";
            throw new QuotaEsaurita("Nel canale " + canale + " " + restano + ": non se ne possono prenotare "
                    + bottiglie);
        }
        quote.put(canale, quota.conPrenotate(quota.prenotate() + bottiglie));
    }

    // ---------------------------------------------------------------- la SAGA del pagamento

    /**
     * Pagamenti ha addebitato la prenotazione (PagamentoRiuscito):
     *  - in attesa di pagamento: confermata, con i numeri di bottiglia
     *    successivi. Anche se il tempo è appena finito ma la scadenza non l'ha
     *    ancora annullata: le bottiglie sono ancora sue;
     *  - annullata (scaduta o rifiutata): le bottiglie non ci sono più, si
     *    chiede di restituire il pagamento, una volta sola;
     *  - già confermata: lo stesso esito arrivato di nuovo, niente da fare.
     */
    public EsitoPrenotazione pagamentoRiuscito(Prenotazione prenotazione, UUID pagamentoId, String riferimento,
                                               Instant adesso) {
        verificaCheSiaDiQuesta(prenotazione);
        return switch (prenotazione.stato()) {
            case IN_ATTESA_PAGAMENTO -> {
                prenotazione.conferma(pagamentoId, riferimento, numera(prenotazione.bottiglie()), adesso);
                yield EsitoPrenotazione.CONFERMATA;
            }
            case ANNULLATA -> {
                if (prenotazione.rimborsoRichiestoIl() != null) {
                    yield EsitoPrenotazione.GIA_REGISTRATO;
                }
                prenotazione.richiediRimborso(pagamentoId, riferimento, adesso);
                yield EsitoPrenotazione.RIMBORSO_RICHIESTO;
            }
            case CONFERMATA -> EsitoPrenotazione.GIA_REGISTRATO;
        };
    }

    /**
     * Pagamenti non ha potuto addebitare (PagamentoFallito): la prenotazione
     * è annullata e le sue bottiglie tornano libere nella quota. Se è già
     * annullata (per esempio scaduta) o confermata, non cambia niente.
     *
     * @param spiegazione quella di Pagamenti, in italiano ("La banca ha rifiutato la carta.")
     */
    public EsitoPrenotazione pagamentoFallito(Prenotazione prenotazione, String spiegazione, Instant adesso) {
        verificaCheSiaDiQuesta(prenotazione);
        if (prenotazione.stato() != StatoPrenotazione.IN_ATTESA_PAGAMENTO) {
            return EsitoPrenotazione.GIA_REGISTRATO;
        }
        libera(prenotazione);
        prenotazione.annulla(CausaAnnullamento.PAGAMENTO_RIFIUTATO,
                spiegazione == null || spiegazione.isBlank() ? "Il pagamento non è riuscito." : spiegazione.trim(),
                adesso);
        return EsitoPrenotazione.ANNULLATA;
    }

    /**
     * Il tempo per pagare è finito: la prenotazione è annullata e le sue
     * bottiglie tornano libere. Se non è scaduta, o non aspetta più il
     * pagamento, non cambia niente.
     *
     * @return true se la prenotazione è stata annullata adesso
     */
    public boolean scadenza(Prenotazione prenotazione, Instant adesso) {
        verificaCheSiaDiQuesta(prenotazione);
        if (prenotazione.stato() != StatoPrenotazione.IN_ATTESA_PAGAMENTO || !prenotazione.scaduta(adesso)) {
            return false;
        }
        libera(prenotazione);
        prenotazione.annulla(CausaAnnullamento.SCADUTA, "Il tempo per pagare è finito il "
                + DATA_E_ORA.format(prenotazione.scadeIl()) + ": le bottiglie sono tornate disponibili.", adesso);
        return true;
    }

    /** I numeri successivi a quelli già dati: dopo 111, per 2 bottiglie, 112 e 113. */
    private List<Integer> numera(int bottiglie) {
        if (bottiglieNumerate + bottiglie > tiratura.bottiglie()) {
            // Non succede: le bottiglie confermate sono prenotate, e le prenotate non superano la tiratura
            throw new IllegalStateException("Numeri di bottiglia oltre la tiratura: " + bottiglieNumerate + " + "
                    + bottiglie + " > " + tiratura.bottiglie());
        }
        List<Integer> numeri = IntStream.rangeClosed(bottiglieNumerate + 1, bottiglieNumerate + bottiglie)
                .boxed().toList();
        bottiglieNumerate += bottiglie;
        return numeri;
    }

    /** Le bottiglie della prenotazione tornano libere nella quota del suo canale. */
    private void libera(Prenotazione prenotazione) {
        Quota quota = quote.get(prenotazione.canale());
        if (quota.prenotate() < prenotazione.bottiglie()) {
            throw new IllegalStateException("Nel canale " + prenotazione.canale() + " ci sono " + quota.prenotate()
                    + " bottiglie prenotate: non se ne liberano " + prenotazione.bottiglie());
        }
        quote.put(prenotazione.canale(), quota.conPrenotate(quota.prenotate() - prenotazione.bottiglie()));
    }

    private void verificaCheSiaDiQuesta(Prenotazione prenotazione) {
        if (!id.equals(prenotazione.edizioneId())) {
            throw new IllegalArgumentException("La prenotazione " + prenotazione.id() + " è dell'edizione "
                    + prenotazione.edizioneId() + ", non di " + id);
        }
    }

    private boolean inPrelazione(Instant adesso) {
        return prelazioneFinoAl != null && adesso.isBefore(prelazioneFinoAl);
    }

    private static String oltreIlLimite(boolean prelazione, int massimo, int giàPrenotate, int bottiglie) {
        String regola = prelazione
                ? "in prelazione ogni socio prenota al massimo " + bottiglie(massimo) + " di un'edizione"
                : "online ogni persona prenota al massimo " + bottiglie(massimo) + " di un'edizione";
        if (giàPrenotate == 0) {
            return "Troppe bottiglie: " + regola + ", non " + bottiglie + ".";
        }
        int restano = massimo - giàPrenotate;
        return "Hai già " + bottiglie(giàPrenotate) + " di questa edizione e " + regola
                + (restano > 0 ? ": puoi aggiungerne ancora " + restano + "." : ": non puoi aggiungerne altre.");
    }

    private static String bottiglie(int numero) {
        return numero == 1 ? "1 bottiglia" : numero + " bottiglie";
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

    /** null solo per le edizioni create prima della prelazione (già in vendita). */
    public Instant prelazioneFinoAl() {
        return prelazioneFinoAl;
    }

    /** null solo per le edizioni create prima che si registrasse l'annuncio. */
    public Integer tiraturaAnnunciata() {
        return tiraturaAnnunciata;
    }

    /** null solo per le edizioni create prima che si registrasse l'annuncio. */
    public BigDecimal botanicaAnnunciataKg() {
        return botanicaAnnunciataKg;
    }

    /** Le bottiglie per canale dell'annuncio (SOCI, BAR, ONLINE); vuote per le edizioni di prima. */
    public Map<Canale, Integer> quoteAnnunciate() {
        EnumMap<Canale, Integer> copia = new EnumMap<>(Canale.class);
        copia.putAll(quoteAnnunciate);
        return Collections.unmodifiableMap(copia);
    }

    /** Quante bottiglie hanno già un numero (l'ultimo numero dato). */
    public int bottiglieNumerate() {
        return bottiglieNumerate;
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
