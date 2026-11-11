package it.ginvault.pagamenti.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Aggregate root: il pagamento di UNA prenotazione (Giulia prenota 2
 * bottiglie dell'edizione d'estate: 90,00 euro).
 *
 * Nasce solo con l'esito di Stripe in mano (registra): RIUSCITO o RIFIUTATO.
 * Un addebito riuscito si può restituire (rimborsato) quando Edizioni lo
 * chiede, perché il pagamento è arrivato a prenotazione già scaduta.
 *
 * Una prenotazione, un pagamento: prenotazione_id è unico. È la seconda
 * difesa contro i doppioni, dopo i comandi già elaborati (comando_elaborato) e
 * insieme alla chiave di idempotenza di Stripe.
 *
 * Gli eventi (PagamentoRiuscito, PagamentoFallito, RimborsoEseguito) partono
 * con repository.save(pagamento): li raccoglie ScritturaOutbox.
 */
@Entity
@Table(name = "pagamento",
        uniqueConstraints = @UniqueConstraint(name = "uk_pagamento_prenotazione", columnNames = "prenotazione_id"))
public class Pagamento {

    private static final int MASSIMO_SPIEGAZIONE = 300;

    @Id
    private UUID id;

    @Column(name = "prenotazione_id", nullable = false, updatable = false)
    private UUID prenotazioneId;

    @Column(name = "edizione_id", nullable = false, updatable = false)
    private UUID edizioneId;

    /** Il "sub" di chi ha prenotato (es. "github:1234567"). */
    @Column(name = "acquirente", nullable = false, length = 100, updatable = false)
    private String acquirente;

    @Column(name = "importo_centesimi", nullable = false, updatable = false)
    private long importoCentesimi;

    @Column(name = "valuta", nullable = false, length = 3, updatable = false)
    private String valuta;

    /** Il metodo di pagamento usato, es. "pm_card_visa". */
    @Column(name = "metodo", nullable = false, length = 64, updatable = false)
    private String metodo;

    @Enumerated(EnumType.STRING)
    @Column(name = "stato", nullable = false, length = 20)
    private StatoPagamento stato;

    /** L'id del PaymentIntent (pi_...); null se Stripe ha rifiutato la richiesta senza crearlo. */
    @Column(name = "riferimento_stripe", length = 100, updatable = false)
    private String riferimentoStripe;

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_rifiuto", length = 30, updatable = false)
    private MotivoRifiuto motivoRifiuto;

    @Column(name = "codice_rifiuto", length = 60, updatable = false)
    private String codiceRifiuto;

    @Column(name = "spiegazione", length = MASSIMO_SPIEGAZIONE, updatable = false)
    private String spiegazione;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    /** L'id del rimborso (re_...), quando c'è. */
    @Column(name = "rimborso_stripe", length = 100)
    private String rimborsoStripe;

    @Column(name = "motivo_rimborso", length = 200)
    private String motivoRimborso;

    @Column(name = "rimborsato_il")
    private Instant rimborsatoIl;

    /** Lock ottimistico: due rimborsi in parallelo non passano entrambi. Null = pagamento nuovo (persist). */
    @Version
    private Long revisione;

    @Transient
    private final List<Object> eventi = new ArrayList<>();

    protected Pagamento() {
        // per JPA
    }

    private Pagamento(RichiestaDiPagamento richiesta, Instant adesso) {
        this.id = UUID.randomUUID();
        this.prenotazioneId = richiesta.prenotazioneId();
        this.edizioneId = richiesta.edizioneId();
        this.acquirente = richiesta.acquirente();
        this.importoCentesimi = richiesta.importoCentesimi();
        this.valuta = richiesta.valuta();
        this.metodo = richiesta.metodo();
        this.creatoIl = adesso;
    }

    /** Il pagamento di una prenotazione, con l'esito che ha dato Stripe. */
    public static Pagamento registra(RichiestaDiPagamento richiesta, EsitoAddebito esito, Instant adesso) {
        Pagamento pagamento = new Pagamento(richiesta, adesso);
        switch (esito) {
            case EsitoAddebito.Riuscito riuscito -> {
                if (riuscito.riferimento() == null || riuscito.riferimento().isBlank()) {
                    throw new IllegalArgumentException("Un addebito riuscito ha il suo PaymentIntent");
                }
                pagamento.stato = StatoPagamento.RIUSCITO;
                pagamento.riferimentoStripe = riuscito.riferimento();
            }
            case EsitoAddebito.Rifiutato rifiutato -> {
                pagamento.stato = StatoPagamento.RIFIUTATO;
                pagamento.riferimentoStripe = rifiutato.riferimento();
                pagamento.motivoRifiuto = rifiutato.motivo();
                pagamento.codiceRifiuto = tronca(rifiutato.codice(), 60);
                pagamento.spiegazione = tronca(rifiutato.spiegazione(), MASSIMO_SPIEGAZIONE);
            }
        }
        pagamento.registraEsito(adesso);
        return pagamento;
    }

    /**
     * Lo stesso addebito chiesto di nuovo (un altro comando per la stessa
     * prenotazione): Stripe non si richiama, si racconta di nuovo l'esito che
     * c'è già. Edizioni scarta i doppioni (stessa prenotazione già confermata).
     */
    public void ripetiEsito(Instant adesso) {
        registraEsito(adesso);
    }

    /** L'esito dell'addebito come lo darebbe Stripe: per non chiamarlo di nuovo. */
    public EsitoAddebito esito() {
        return stato == StatoPagamento.RIFIUTATO
                ? new EsitoAddebito.Rifiutato(motivoRifiuto, codiceRifiuto, spiegazione, riferimentoStripe)
                : new EsitoAddebito.Riuscito(riferimentoStripe);
    }

    /** Prima di chiamare Stripe: un pagamento rifiutato non ha niente da restituire. */
    public void controllaRimborsabile() {
        if (stato == StatoPagamento.RIFIUTATO) {
            throw new RimborsoNonPossibile("Il pagamento della prenotazione " + prenotazioneId
                    + " era stato rifiutato: non c'è niente da rimborsare");
        }
    }

    /**
     * Stripe ha restituito l'addebito. Già rimborsato (lo stesso comando
     * rielaborato): niente cambia, si racconta di nuovo il rimborso.
     *
     * @param rimborso l'id del rimborso di Stripe (re_...)
     */
    public void rimborsato(String rimborso, String motivo, Instant adesso) {
        controllaRimborsabile();
        if (stato == StatoPagamento.RIUSCITO) {
            if (rimborso == null || rimborso.isBlank()) {
                throw new IllegalArgumentException("Un rimborso ha il suo id di Stripe");
            }
            stato = StatoPagamento.RIMBORSATO;
            rimborsoStripe = rimborso;
            motivoRimborso = tronca(motivo, 200);
            rimborsatoIl = adesso;
        }
        eventi.add(new RimborsoEseguito(id, prenotazioneId, edizioneId, acquirente, importoCentesimi, valuta,
                rimborsoStripe, motivoRimborso, adesso));
    }

    private void registraEsito(Instant adesso) {
        if (stato == StatoPagamento.RIFIUTATO) {
            eventi.add(new PagamentoFallito(id, prenotazioneId, edizioneId, acquirente, motivoRifiuto, codiceRifiuto,
                    spiegazione, adesso));
        } else {
            // RIUSCITO, o RIMBORSATO dopo: l'addebito era comunque riuscito
            eventi.add(new PagamentoRiuscito(id, prenotazioneId, edizioneId, acquirente, importoCentesimi, valuta,
                    riferimentoStripe, adesso));
        }
    }

    private static String tronca(String testo, int massimo) {
        return testo == null || testo.length() <= massimo ? testo : testo.substring(0, massimo);
    }

    /** Pubblicati da repository.save(pagamento): li raccoglie ScritturaOutbox. */
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

    public UUID prenotazioneId() {
        return prenotazioneId;
    }

    public UUID edizioneId() {
        return edizioneId;
    }

    public String acquirente() {
        return acquirente;
    }

    public long importoCentesimi() {
        return importoCentesimi;
    }

    public String valuta() {
        return valuta;
    }

    public String metodo() {
        return metodo;
    }

    public StatoPagamento stato() {
        return stato;
    }

    public String riferimentoStripe() {
        return riferimentoStripe;
    }

    public MotivoRifiuto motivoRifiuto() {
        return motivoRifiuto;
    }

    public String codiceRifiuto() {
        return codiceRifiuto;
    }

    public String spiegazione() {
        return spiegazione;
    }

    public Instant creatoIl() {
        return creatoIl;
    }

    public String rimborsoStripe() {
        return rimborsoStripe;
    }

    public String motivoRimborso() {
        return motivoRimborso;
    }

    public Instant rimborsatoIl() {
        return rimborsatoIl;
    }
}
