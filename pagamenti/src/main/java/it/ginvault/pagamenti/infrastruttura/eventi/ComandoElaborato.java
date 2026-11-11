package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.domain.Persistable;

/**
 * Un comando già elaborato: la tabella che rende IDEMPOTENTE il consumer
 * (come evento_elaborato negli altri servizi).
 *
 * L'outbox di Edizioni garantisce "almeno una volta": lo stesso comando
 * (stesso eventId della busta) può arrivare due volte. La riga si scrive nella
 * STESSA transazione del pagamento salvato e della sua riga di outbox: se c'è
 * la riga, c'è anche l'esito, e al secondo arrivo il comando si scarta senza
 * chiamare Stripe.
 *
 * event_id è la chiave primaria: anche due elaborazioni in parallelo dello
 * stesso comando non passano entrambe (la seconda fallisce al commit e, al
 * nuovo tentativo, trova la riga).
 */
@Entity
@Table(name = "comando_elaborato")
public class ComandoElaborato implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "tipo", nullable = false, length = 60, updatable = false)
    private String tipo;

    /** Da dove è arrivato, per ritrovare il messaggio: topic, partizione e offset. */
    @Column(name = "topic", nullable = false, length = 100, updatable = false)
    private String topic;

    @Column(name = "partizione", nullable = false, updatable = false)
    private int partizione;

    @Column(name = "posizione", nullable = false, updatable = false)
    private long offset;

    @Column(name = "elaborato_il", nullable = false, updatable = false)
    private Instant elaboratoIl;

    protected ComandoElaborato() {
        // per JPA
    }

    ComandoElaborato(UUID eventId, String tipo, String topic, int partizione, long offset, Instant elaboratoIl) {
        this.eventId = eventId;
        this.tipo = tipo;
        this.topic = topic;
        this.partizione = partizione;
        this.offset = offset;
        this.elaboratoIl = elaboratoIl;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    /**
     * Sempre nuova: save fa una INSERT (persist) e non una SELECT seguita da un
     * merge. Se la riga esiste già, l'INSERT fallisce invece di sovrascriverla.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public String tipo() {
        return tipo;
    }

    public String topic() {
        return topic;
    }

    public int partizione() {
        return partizione;
    }

    public long offset() {
        return offset;
    }

    public Instant elaboratoIl() {
        return elaboratoIl;
    }
}
