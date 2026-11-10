package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import it.ginvault.magazzino.dominio.RiservaRifiutata.Motivo;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Aggregate root: un lotto di botanica in magazzino, con la sua tracciabilità
 * (da dove viene, da quale fornitore e ordine, quando scade, se ha passato il
 * controllo qualità).
 *
 * Nasce da una consegna (OrdineDiAcquisto.registraConsegna) oppure senza
 * ordine, come il lotto dei campi dell'azienda (DatiIniziali).
 *
 * Rete di sicurezza: UNIQUE su ordine_id. Anche con due richieste nello stesso
 * istante, un ordine non genera due lotti.
 *
 * Riserve (riserva, chiamata da Produzione via gRPC): un piano di produzione
 * mette da parte una parte del lotto. Regole:
 *  - una riserva per piano (UNIQUE lotto_id + piano_id); la stessa richiesta
 *    ripetuta restituisce quella che c'è, così si può ripetere senza paura
 *    dopo una deadline scaduta;
 *  - solo se il lotto è utilizzabile (conforme e non scaduto);
 *  - mai più dei kg liberi: kg - Σ riservati. Due riserve nello stesso istante
 *    sullo stesso lotto: la @Version fa fallire la seconda, che riprova;
 *  - una riserva si può annullare (annullaRiserva, la compensazione di
 *    Produzione): i kg tornano liberi. Anche questo è idempotente.
 *
 * L'origine NON è una regola del magazzino: un lotto dei campi si può
 * riservare per qualunque piano. Decide Produzione, che sa da dove deve venire
 * la botanica dell'edizione.
 */
@Entity
@Table(name = "lotto_di_botanica",
        uniqueConstraints = @UniqueConstraint(name = "uk_lotto_ordine", columnNames = "ordine_id"),
        indexes = @Index(name = "ix_lotto_botanica", columnList = "botanica"))
public class LottoDiBotanica {

    @Id
    private UUID id;

    @Column(name = "botanica", nullable = false, length = 50)
    private String botanica;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    @Column(name = "kg", nullable = false, precision = 8, scale = 3)
    private BigDecimal kg;

    @Column(name = "scadenza", nullable = false)
    private LocalDate scadenza;

    @Enumerated(EnumType.STRING)
    @Column(name = "esito_qualita", nullable = false, length = 15)
    private EsitoQualita esitoQualita;

    @Column(name = "fornitore_id", nullable = false)
    private UUID fornitoreId;

    /** null per i lotti che non vengono da un ordine (es. i campi dell'azienda). */
    @Column(name = "ordine_id")
    private UUID ordineId;

    @Column(name = "ricevuto_il", nullable = false)
    private LocalDate ricevutoIl;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    /** Le parti del lotto messe da parte per i piani di produzione, in ordine di arrivo. */
    @ElementCollection
    @CollectionTable(name = "lotto_riserva", joinColumns = @JoinColumn(name = "lotto_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_lotto_riserva_piano",
                    columnNames = {"lotto_id", "piano_id"}))
    @OrderColumn(name = "posizione")
    private List<RiservaDiLotto> riserve = new ArrayList<>();

    /** Blocco ottimistico: anche una riserva aggiunta incrementa la revisione del lotto. */
    @Version
    private Long revisione;

    protected LottoDiBotanica() {
        // per JPA
    }

    private LottoDiBotanica(UUID id, String botanica, String origine, BigDecimal kg, LocalDate scadenza,
                            EsitoQualita esitoQualita, UUID fornitoreId, UUID ordineId, LocalDate ricevutoIl,
                            Instant adesso) {
        this.id = id;
        this.botanica = botanica;
        this.origine = origine;
        this.kg = kg;
        this.scadenza = scadenza;
        this.esitoQualita = esitoQualita;
        this.fornitoreId = fornitoreId;
        this.ordineId = ordineId;
        this.ricevutoIl = ricevutoIl;
        this.creatoIl = adesso;
    }

    /** Dalla consegna di un ordine: i valori li ha già controllati l'ordine. */
    static LottoDiBotanica daConsegna(OrdineDiAcquisto ordine, String origine, BigDecimal kg, LocalDate scadenza,
                                      EsitoQualita esito, LocalDate consegnataIl, Instant adesso) {
        return new LottoDiBotanica(UUID.randomUUID(), ordine.botanica(), origine, kg, scadenza, esito,
                ordine.fornitoreId(), ordine.id(), consegnataIl, adesso);
    }

    /** Un lotto senza ordine (es. dai campi dell'azienda), con un id scelto da chi lo crea. */
    public static LottoDiBotanica senzaOrdine(UUID id, String botanica, String origine, BigDecimal kg,
                                              LocalDate scadenza, EsitoQualita esito, UUID fornitoreId,
                                              LocalDate ricevutoIl, Instant adesso) {
        if (origine == null || origine.isBlank()) {
            throw new OrdineNonValido("Serve l'origine del lotto");
        }
        if (scadenza == null || ricevutoIl == null || !scadenza.isAfter(ricevutoIl)) {
            throw new OrdineNonValido("La scadenza del lotto deve venire dopo la data di arrivo");
        }
        return new LottoDiBotanica(id, Botaniche.normalizza(botanica), Botaniche.spaziSingoli(origine),
                Chilogrammi.verifica(kg, "I kg del lotto", OrdineNonValido::new), scadenza, esito, fornitoreId,
                null, ricevutoIl, adesso);
    }

    /** Si può usare per produrre: controllo qualità superato e non scaduto. */
    public boolean utilizzabile(LocalDate oggi) {
        return esitoQualita == EsitoQualita.CONFORME && !scadenza.isBefore(oggi);
    }

    /**
     * Mette da parte dei kg del lotto per un piano di produzione (vedi le regole in cima).
     *
     * @param chi chi la chiede (il "sub" del JWT), per la tracciabilità
     * @throws RiservaRifiutata dati non validi, lotto non utilizzabile, kg insufficienti,
     *                          oppure il piano ha già riservato altri kg di questo lotto
     */
    public EsitoRiserva riserva(UUID pianoId, BigDecimal kgRichiesti, LocalDate oggi, String chi, Instant adesso) {
        if (pianoId == null) {
            throw new RiservaRifiutata(Motivo.DATI_NON_VALIDI, "Serve il piano di produzione per cui riservare");
        }
        if (chi == null || chi.isBlank()) {
            throw new RiservaRifiutata(Motivo.DATI_NON_VALIDI, "Serve chi chiede la riserva");
        }
        BigDecimal kg = Chilogrammi.verifica(kgRichiesti, "I kg da riservare",
                messaggio -> new RiservaRifiutata(Motivo.DATI_NON_VALIDI, messaggio));

        // Prima la richiesta ripetuta: deve dare la stessa risposta anche se nel
        // frattempo il lotto è scaduto o finito
        Optional<RiservaDiLotto> esistente = riservaDel(pianoId);
        if (esistente.isPresent()) {
            BigDecimal giaRiservati = esistente.get().kg();
            if (giaRiservati.compareTo(kg) != 0) {
                throw new RiservaRifiutata(Motivo.GIA_RISERVATO, "Il piano " + pianoId + " ha già riservato "
                        + Chilogrammi.testo(giaRiservati) + " kg di questo lotto, non " + Chilogrammi.testo(kg));
            }
            return new EsitoRiserva(giaRiservati, kgDisponibili(), true);
        }
        if (esitoQualita != EsitoQualita.CONFORME) {
            throw new RiservaRifiutata(Motivo.NON_UTILIZZABILE,
                    "Il lotto non ha superato il controllo qualità: non si può usare per produrre");
        }
        if (scadenza.isBefore(oggi)) {
            throw new RiservaRifiutata(Motivo.NON_UTILIZZABILE, "Il lotto è scaduto il " + scadenza);
        }
        BigDecimal liberi = kgDisponibili();
        if (kg.compareTo(liberi) > 0) {
            throw new RiservaRifiutata(Motivo.KG_INSUFFICIENTI, "Il lotto ha " + Chilogrammi.testo(liberi)
                    + " kg liberi: non se ne possono riservare " + Chilogrammi.testo(kg));
        }
        riserve.add(new RiservaDiLotto(pianoId, kg, chi, adesso));
        return new EsitoRiserva(kg, kgDisponibili(), false);
    }

    /**
     * Toglie la riserva del piano e ne libera i kg: la compensazione di
     * Produzione quando non riesce a salvare il piano dopo aver riservato.
     * Idempotente: senza riserva non cambia niente. Si può annullare sempre,
     * anche un lotto scaduto o non conforme: liberare kg non fa danni.
     *
     * @return la riserva tolta, vuoto se il piano non ne aveva
     */
    public Optional<RiservaDiLotto> annullaRiserva(UUID pianoId) {
        if (pianoId == null) {
            throw new RiservaRifiutata(Motivo.DATI_NON_VALIDI, "Serve il piano di produzione di cui annullare la riserva");
        }
        Optional<RiservaDiLotto> riserva = riservaDel(pianoId);
        riserva.ifPresent(riserve::remove);
        return riserva;
    }

    /** I kg non ancora riservati: kg - Σ riservati. */
    public BigDecimal kgDisponibili() {
        return riserve.stream().map(RiservaDiLotto::kg).reduce(kg, BigDecimal::subtract);
    }

    public Optional<RiservaDiLotto> riservaDel(UUID pianoId) {
        return riserve.stream().filter(riserva -> riserva.pianoId().equals(pianoId)).findFirst();
    }

    public List<RiservaDiLotto> riserve() {
        return List.copyOf(riserve);
    }

    public UUID id() {
        return id;
    }

    public String botanica() {
        return botanica;
    }

    public String origine() {
        return origine;
    }

    public BigDecimal kg() {
        return kg;
    }

    public LocalDate scadenza() {
        return scadenza;
    }

    public EsitoQualita esitoQualita() {
        return esitoQualita;
    }

    public UUID fornitoreId() {
        return fornitoreId;
    }

    public UUID ordineId() {
        return ordineId;
    }

    public LocalDate ricevutoIl() {
        return ricevutoIl;
    }
}
