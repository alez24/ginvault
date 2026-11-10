package it.ginvault.edizioni.infrastruttura.eventi;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.domain.Persistable;

/**
 * Un evento già elaborato: la tabella che rende IDEMPOTENTE il consumer, come
 * in Produzione.
 *
 * Kafka e l'outbox garantiscono "almeno una volta": la stessa ResaRicalcolata
 * (stesso eventId) può arrivare due volte. Riapplicarla subito non farebbe
 * danni (stessi kg, stessa tiratura), ma arrivata dopo una resa più recente
 * riporterebbe l'edizione indietro. La riga si scrive nella STESSA transazione
 * dell'effetto (edizione ricalcolata, riga di outbox): se c'è la riga, c'è
 * anche l'effetto, e al secondo arrivo l'evento si scarta.
 *
 * event_id è la chiave primaria: anche due elaborazioni in parallelo dello
 * stesso evento non passano entrambe.
 */
@Entity
@Table(name = "evento_elaborato")
public class EventoElaborato implements Persistable<UUID> {

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

    /** Com'è andata: RIDOTTA, AUMENTATA, INVARIATA, RIFIUTATO. */
    @Column(name = "esito", nullable = false, length = 20, updatable = false)
    private String esito;

    @Column(name = "elaborato_il", nullable = false, updatable = false)
    private Instant elaboratoIl;

    protected EventoElaborato() {
        // per JPA
    }

    EventoElaborato(UUID eventId, String tipo, String topic, int partizione, long offset, String esito,
                    Instant elaboratoIl) {
        this.eventId = eventId;
        this.tipo = tipo;
        this.topic = topic;
        this.partizione = partizione;
        this.offset = offset;
        this.esito = esito;
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

    public String esito() {
        return esito;
    }
}
