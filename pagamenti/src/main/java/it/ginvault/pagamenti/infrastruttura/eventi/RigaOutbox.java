package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Una riga della tabella outbox: un evento da mandare su Kafka, già scritto
 * come messaggio (la busta JSON di docs/eventi.md).
 *
 * Si scrive nella stessa transazione del cambiamento che l'evento racconta
 * (ScritturaOutbox): o ci sono entrambi, o nessuno dei due. La svuota
 * PubblicatoreOutbox, che segna pubblicato_il quando Kafka ha confermato.
 *
 * numero: ordine di scrittura, per pubblicare gli eventi nello stesso ordine.
 */
@Entity
@Table(name = "outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"),
        indexes = @Index(name = "ix_outbox_da_pubblicare", columnList = "pubblicato_il, numero"))
public class RigaOutbox {

    private static final int MASSIMO_ERRORE = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long numero;

    /** L'eventId della busta: lo stesso a ogni tentativo, il consumer lo usa per scartare i doppioni. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "topic", nullable = false, length = 100, updatable = false)
    private String topic;

    /** La chiave del messaggio Kafka (qui: l'id della prenotazione): sceglie la partizione. */
    @Column(name = "chiave", nullable = false, length = 100, updatable = false)
    private String chiave;

    @Column(name = "tipo", nullable = false, length = 60, updatable = false)
    private String tipo;

    /** Il valore del messaggio Kafka: il JSON della busta. */
    @Column(name = "messaggio", nullable = false, columnDefinition = "text", updatable = false)
    private String messaggio;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    /** null finché Kafka non ha confermato. */
    @Column(name = "pubblicato_il")
    private Instant pubblicatoIl;

    @Column(name = "tentativi", nullable = false)
    private int tentativi;

    @Column(name = "ultimo_errore", length = MASSIMO_ERRORE)
    private String ultimoErrore;

    protected RigaOutbox() {
        // per JPA
    }

    RigaOutbox(UUID eventId, String topic, String chiave, String tipo, String messaggio, Instant creatoIl) {
        this.eventId = eventId;
        this.topic = topic;
        this.chiave = chiave;
        this.tipo = tipo;
        this.messaggio = messaggio;
        this.creatoIl = creatoIl;
    }

    void pubblicata(Instant adesso) {
        tentativi++;
        pubblicatoIl = adesso;
        ultimoErrore = null;
    }

    void fallita(Throwable errore) {
        tentativi++;
        String testo = errore.getClass().getSimpleName() + ": " + errore.getMessage();
        ultimoErrore = testo.length() > MASSIMO_ERRORE ? testo.substring(0, MASSIMO_ERRORE) : testo;
    }

    public Long numero() {
        return numero;
    }

    public UUID eventId() {
        return eventId;
    }

    public String topic() {
        return topic;
    }

    public String chiave() {
        return chiave;
    }

    public String tipo() {
        return tipo;
    }

    public String messaggio() {
        return messaggio;
    }

    public Instant pubblicatoIl() {
        return pubblicatoIl;
    }

    public int tentativi() {
        return tentativi;
    }

    public String ultimoErrore() {
        return ultimoErrore;
    }
}
