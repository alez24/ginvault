package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Aggregate root: le bottiglie di un'edizione prenotate da una persona.
 *
 * Nasce solo da Edizione.prenota(), che controlla prelazione, limite per
 * persona e bottiglie libere, e nello stesso momento le toglie dalla quota.
 * Per questo il costruttore non è pubblico.
 *
 * Perché un aggregato a parte e non una lista dentro Edizione:
 *  - ogni prenotazione avrà la sua storia (il pagamento, la SAGA con
 *    Pagamenti), che cambia senza toccare l'edizione;
 *  - un'edizione può averne centinaia: caricarle tutte a ogni ricalcolo
 *    della tiratura non servirebbe.
 * La regola "le prenotate non superano mai le bottiglie della quota" resta
 * all'Edizione, che tiene il conto per canale (Quota.prenotate). La
 * prenotazione si riferisce all'edizione solo per id.
 *
 * Il prezzo è quello del momento in cui si prenota: se il listino cambia,
 * una prenotazione già fatta non cambia.
 */
@Entity
@Table(name = "prenotazione", indexes = {
        @Index(name = "ix_prenotazione_edizione_acquirente", columnList = "edizione_id, acquirente"),
        @Index(name = "ix_prenotazione_acquirente", columnList = "acquirente")})
public class Prenotazione {

    @Id
    private UUID id;

    @Column(name = "edizione_id", nullable = false, updatable = false)
    private UUID edizioneId;

    /** Il "sub" del JWT di chi ha prenotato (es. "github:1234567"). */
    @Column(name = "acquirente", nullable = false, updatable = false, length = 100)
    private String acquirente;

    /** SOCI in prelazione, ONLINE dopo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "canale", nullable = false, updatable = false, length = 10)
    private Canale canale;

    @Column(name = "bottiglie", nullable = false, updatable = false)
    private int bottiglie;

    @Column(name = "prezzo_per_bottiglia", nullable = false, updatable = false, precision = 7, scale = 2)
    private BigDecimal prezzoPerBottiglia;

    @Enumerated(EnumType.STRING)
    @Column(name = "stato", nullable = false, length = 25)
    private StatoPrenotazione stato;

    @Column(name = "prenotata_il", nullable = false, updatable = false)
    private Instant prenotataIl;

    /** Entro quando va pagata; dopo, la prenotazione si annulla e le bottiglie tornano libere. */
    @Column(name = "scade_il", nullable = false)
    private Instant scadeIl;

    /** Blocco ottimistico; null = prenotazione nuova, mai salvata. */
    @Version
    private Long revisione;

    protected Prenotazione() {
        // per JPA
    }

    private Prenotazione(UUID edizioneId, String acquirente, Canale canale, int bottiglie,
                         BigDecimal prezzoPerBottiglia, Instant prenotataIl, Instant scadeIl) {
        this.id = UUID.randomUUID();
        this.edizioneId = edizioneId;
        this.acquirente = acquirente;
        this.canale = canale;
        this.bottiglie = bottiglie;
        this.prezzoPerBottiglia = prezzoPerBottiglia;
        this.stato = StatoPrenotazione.IN_ATTESA_PAGAMENTO;
        this.prenotataIl = prenotataIl;
        this.scadeIl = scadeIl;
    }

    /** Solo per Edizione.prenota(), dopo i controlli: la prenotazione nasce in attesa di pagamento. */
    static Prenotazione nuova(UUID edizioneId, String acquirente, Canale canale, int bottiglie,
                              BigDecimal prezzoPerBottiglia, Instant prenotataIl, Instant scadeIl) {
        return new Prenotazione(edizioneId, acquirente, canale, bottiglie, prezzoPerBottiglia, prenotataIl, scadeIl);
    }

    // ---------------------------------------------------------------- lettura

    public UUID id() {
        return id;
    }

    public UUID edizioneId() {
        return edizioneId;
    }

    public String acquirente() {
        return acquirente;
    }

    public Canale canale() {
        return canale;
    }

    public int bottiglie() {
        return bottiglie;
    }

    public BigDecimal prezzoPerBottiglia() {
        return prezzoPerBottiglia;
    }

    /** Prezzo per bottiglia per il numero di bottiglie, al centesimo. */
    public BigDecimal totale() {
        return prezzoPerBottiglia.multiply(BigDecimal.valueOf(bottiglie));
    }

    public StatoPrenotazione stato() {
        return stato;
    }

    /** Vero finché la prenotazione tiene occupate bottiglie della quota (cioè se non è annullata). */
    public boolean occupaBottiglie() {
        return stato.occupaBottiglie();
    }

    public Instant prenotataIl() {
        return prenotataIl;
    }

    public Instant scadeIl() {
        return scadeIl;
    }
}
