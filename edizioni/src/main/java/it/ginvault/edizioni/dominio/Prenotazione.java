package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Aggregate root: le bottiglie di un'edizione prenotate da una persona.
 *
 * Nasce solo da Edizione.prenota(), che controlla prelazione, limite per
 * persona e bottiglie libere, e nello stesso momento le toglie dalla quota.
 * Per questo il costruttore non è pubblico.
 *
 * Perché un aggregato a parte e non una lista dentro Edizione:
 *  - ogni prenotazione ha la sua storia (il pagamento, la SAGA con
 *    Pagamenti), che cambia senza toccare l'edizione;
 *  - un'edizione può averne centinaia: caricarle tutte a ogni ricalcolo
 *    della tiratura non servirebbe.
 * La regola "le prenotate non superano mai le bottiglie della quota" resta
 * all'Edizione, che tiene il conto per canale (Quota.prenotate) e numera le
 * bottiglie. Per questo conferma, annullamento e scadenza passano da Edizione
 * (pagamentoRiuscito, pagamentoFallito, scadenza), sempre con la riga
 * dell'edizione bloccata. La prenotazione si riferisce all'edizione solo per id.
 *
 * La SAGA (docs/eventi.md, "I messaggi della storia di Giulia"):
 *
 *   IN_ATTESA_PAGAMENTO --richiediPagamento--> (comando RichiestaPagamento)
 *       --PagamentoRiuscito--> CONFERMATA, con i numeri di bottiglia
 *       --PagamentoFallito---> ANNULLATA (PAGAMENTO_RIFIUTATO), bottiglie libere
 *       --tempo finito-------> ANNULLATA (SCADUTA), bottiglie libere
 *   ANNULLATA --PagamentoRiuscito arrivato tardi--> rimborso richiesto
 *       --RimborsoEseguito--> rimborsata
 *
 * Il prezzo è quello del momento in cui si prenota: se il listino cambia,
 * una prenotazione già fatta non cambia.
 */
@Entity
@Table(name = "prenotazione", indexes = {
        @Index(name = "ix_prenotazione_edizione_acquirente", columnList = "edizione_id, acquirente"),
        @Index(name = "ix_prenotazione_acquirente", columnList = "acquirente"),
        @Index(name = "ix_prenotazione_scadenza", columnList = "stato, scade_il")})
public class Prenotazione {

    /** Pagamenti e Stripe vogliono gli importi in euro: "eur". */
    public static final String VALUTA = "eur";

    private static final int MASSIMO_SPIEGAZIONE = 300;

    private static final DateTimeFormatter ORA = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
            .withZone(ZoneId.of("Europe/Rome"));

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

    // ---------------------------------------------------------------- il pagamento

    /** La carta di prova scelta (pm_card_visa, pm_card_chargeDeclined); null finché non si chiede di pagare. */
    @Column(name = "metodo_pagamento", length = 64)
    private String metodoPagamento;

    @Column(name = "pagamento_richiesto_il")
    private Instant pagamentoRichiestoIl;

    /** Il pagamento in Pagamenti e il suo riferimento in Stripe (pi_...), dall'esito riuscito. */
    @Column(name = "pagamento_id")
    private UUID pagamentoId;

    @Column(name = "riferimento_pagamento", length = 100)
    private String riferimentoPagamento;

    @Column(name = "confermata_il")
    private Instant confermataIl;

    /** I numeri di bottiglia, assegnati alla conferma; vuoti prima e per le annullate. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bottiglia_numerata",
            joinColumns = @JoinColumn(name = "prenotazione_id", nullable = false),
            uniqueConstraints = @UniqueConstraint(name = "uk_bottiglia_numerata",
                    columnNames = {"edizione_id", "numero"}))
    @OrderBy("numero")
    private List<NumeroDiBottiglia> numeri = new ArrayList<>();

    // ---------------------------------------------------------------- annullamento e rimborso

    @Column(name = "annullata_il")
    private Instant annullataIl;

    @Enumerated(EnumType.STRING)
    @Column(name = "causa_annullamento", length = 25)
    private CausaAnnullamento causaAnnullamento;

    @Column(name = "spiegazione_annullamento", length = MASSIMO_SPIEGAZIONE)
    private String spiegazioneAnnullamento;

    /** Un pagamento riuscito arrivato a prenotazione annullata: quando si è chiesto di restituirlo. */
    @Column(name = "rimborso_richiesto_il")
    private Instant rimborsoRichiestoIl;

    @Column(name = "rimborsata_il")
    private Instant rimborsataIl;

    /** L'id del rimborso in Stripe (re_...). */
    @Column(name = "riferimento_rimborso", length = 100)
    private String riferimentoRimborso;

    /** Blocco ottimistico; null = prenotazione nuova, mai salvata. */
    @Version
    private Long revisione;

    /** Eventi di dominio in attesa di pubblicazione: non vanno nel database. */
    @Transient
    private final List<Object> eventi = new ArrayList<>();

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

    // ---------------------------------------------------------------- la SAGA

    /**
     * Chi ha prenotato sceglie la carta e chiede di pagare: parte il comando
     * RichiestaPagamento (evento PagamentoRichiesto). Una sola volta: con una
     * carta rifiutata la prenotazione si annulla, e si prenota di nuovo.
     *
     * @throws PagamentoNonPossibile già chiesto, tempo finito, prenotazione confermata o annullata (-> 409)
     */
    public void richiediPagamento(String metodo, String descrizione, Instant adesso) {
        if (stato != StatoPrenotazione.IN_ATTESA_PAGAMENTO) {
            throw new PagamentoNonPossibile(PagamentoNonPossibile.Motivo.PRENOTAZIONE_CHIUSA,
                    "La prenotazione è " + (stato == StatoPrenotazione.CONFERMATA ? "già confermata" : "annullata")
                            + ": non c'è niente da pagare.");
        }
        if (pagamentoRichiestoIl != null) {
            throw new PagamentoNonPossibile(PagamentoNonPossibile.Motivo.PAGAMENTO_GIA_RICHIESTO,
                    "Il pagamento è già in corso: tra pochi secondi la prenotazione sarà confermata o annullata.");
        }
        if (scaduta(adesso)) {
            throw new PagamentoNonPossibile(PagamentoNonPossibile.Motivo.PRENOTAZIONE_SCADUTA,
                    "Il tempo per pagare è finito alle " + ORA.format(scadeIl)
                            + ": le bottiglie tornano disponibili e si può prenotare di nuovo.");
        }
        if (metodo == null || metodo.isBlank()) {
            throw new IllegalArgumentException("Serve il metodo di pagamento");
        }
        metodoPagamento = metodo;
        pagamentoRichiestoIl = adesso;
        eventi.add(new PagamentoRichiesto(id, edizioneId, acquirente, importoCentesimi(), VALUTA, metodo,
                descrizione, adesso));
    }

    /** Solo per Edizione.pagamentoRiuscito, con i numeri appena assegnati. */
    void conferma(UUID pagamento, String riferimento, List<Integer> numeriAssegnati, Instant adesso) {
        if (numeriAssegnati.size() != bottiglie) {
            throw new IllegalArgumentException("Servono " + bottiglie + " numeri di bottiglia, non "
                    + numeriAssegnati.size());
        }
        stato = StatoPrenotazione.CONFERMATA;
        pagamentoId = pagamento;
        riferimentoPagamento = riferimento;
        confermataIl = adesso;
        numeriAssegnati.forEach(numero -> numeri.add(new NumeroDiBottiglia(edizioneId, numero)));
        eventi.add(new PrenotazioneConfermata(id, edizioneId, acquirente, canale, bottiglie, numeriDiBottiglia(),
                totale(), pagamento, adesso));
    }

    /** Solo per Edizione.pagamentoFallito e Edizione.scadenza, dopo aver liberato le bottiglie. */
    void annulla(CausaAnnullamento causa, String spiegazione, Instant adesso) {
        stato = StatoPrenotazione.ANNULLATA;
        annullataIl = adesso;
        causaAnnullamento = causa;
        spiegazioneAnnullamento = spiegazione == null || spiegazione.length() <= MASSIMO_SPIEGAZIONE
                ? spiegazione : spiegazione.substring(0, MASSIMO_SPIEGAZIONE);
        eventi.add(new PrenotazioneAnnullata(id, edizioneId, acquirente, canale, bottiglie, causa,
                spiegazioneAnnullamento, adesso));
    }

    /** Solo per Edizione.pagamentoRiuscito: un pagamento arrivato a prenotazione annullata si restituisce. */
    void richiediRimborso(UUID pagamento, String riferimento, Instant adesso) {
        pagamentoId = pagamento;
        riferimentoPagamento = riferimento;
        rimborsoRichiestoIl = adesso;
        String motivo = causaAnnullamento == CausaAnnullamento.SCADUTA
                ? "pagamento arrivato a prenotazione scaduta" : "pagamento arrivato a prenotazione annullata";
        eventi.add(new RimborsoRichiesto(id, edizioneId, motivo, adesso));
    }

    /**
     * Pagamenti ha restituito il pagamento arrivato tardi (RimborsoEseguito).
     * Lo stesso esito arrivato di nuovo non cambia niente.
     */
    public EsitoPrenotazione rimborsata(String riferimento, Instant adesso) {
        if (rimborsataIl != null) {
            return EsitoPrenotazione.GIA_REGISTRATO;
        }
        rimborsataIl = adesso;
        riferimentoRimborso = riferimento;
        return EsitoPrenotazione.RIMBORSATA;
    }

    /** Il tempo per pagare è finito (scadeIl compreso). */
    public boolean scaduta(Instant adesso) {
        return !adesso.isBefore(scadeIl);
    }

    /** Il totale in centesimi: 2 bottiglie a 45,00 euro -> 9000. */
    public long importoCentesimi() {
        return totale().movePointRight(2).longValueExact();
    }

    // ---------------------------------------------------------------- eventi di dominio

    /** Pubblicati da repository.save(prenotazione): li raccoglie ScritturaOutbox. */
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

    public String metodoPagamento() {
        return metodoPagamento;
    }

    public Instant pagamentoRichiestoIl() {
        return pagamentoRichiestoIl;
    }

    public UUID pagamentoId() {
        return pagamentoId;
    }

    public String riferimentoPagamento() {
        return riferimentoPagamento;
    }

    public Instant confermataIl() {
        return confermataIl;
    }

    /** I numeri di bottiglia in ordine (112, 113); vuoti se non è confermata. */
    public List<Integer> numeriDiBottiglia() {
        return numeri.stream().map(NumeroDiBottiglia::numero).sorted().toList();
    }

    public Instant annullataIl() {
        return annullataIl;
    }

    public CausaAnnullamento causaAnnullamento() {
        return causaAnnullamento;
    }

    public String spiegazioneAnnullamento() {
        return spiegazioneAnnullamento;
    }

    public Instant rimborsoRichiestoIl() {
        return rimborsoRichiestoIl;
    }

    public Instant rimborsataIl() {
        return rimborsataIl;
    }

    public String riferimentoRimborso() {
        return riferimentoRimborso;
    }
}
