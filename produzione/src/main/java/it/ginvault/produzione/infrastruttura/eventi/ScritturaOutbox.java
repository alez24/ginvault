package it.ginvault.produzione.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.produzione.dominio.ResaRicalcolata;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prima metà della TRANSACTIONAL OUTBOX, come in Magazzino ed Edizioni:
 * trasforma gli eventi di dominio in messaggi (la busta JSON di
 * docs/eventi.md) e li scrive nella tabella outbox.
 *
 * phase = BEFORE_COMMIT: gira dentro la transazione di ElaborazioneEventi,
 * appena prima del commit. Nello stesso commit finiscono il piano aggiornato,
 * l'eventId della ConsegnaRegistrata elaborata e la riga di ResaRicalcolata:
 * o tutto, o niente. Se il commit fallisce, Kafka ripropone la consegna e il
 * ricalcolo si rifà da capo, senza doppioni.
 */
@Component
public class ScritturaOutbox {

    public static final String TOPIC = "produzione.eventi";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ScritturaOutbox(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void resaRicalcolata(ResaRicalcolata evento) {
        ResaRicalcolataV1 payload = new ResaRicalcolataV1(evento.edizioneId(), evento.pianoId(), evento.botanica(),
                evento.kgPrevisti(), evento.kgDisponibiliPrima(), evento.kgDisponibili(), evento.ordineId(),
                evento.lottoId(), evento.motivo());
        scrivi("ResaRicalcolata", 1, evento.edizioneId(), evento.occorsoIl(), payload);
    }

    /** Chiave = id dell'edizione: gli eventi della stessa edizione vanno nella stessa partizione, in ordine. */
    private void scrivi(String tipo, int versione, UUID edizioneId, Instant occorsoIl, Object payload) {
        UUID eventId = UUID.randomUUID();
        Busta busta = new Busta(eventId, tipo, versione, occorsoIl, payload);
        try {
            outbox.save(new RigaOutbox(eventId, TOPIC, edizioneId.toString(), tipo, json.writeValueAsString(busta),
                    occorsoIl));
        } catch (JsonProcessingException errore) {
            throw new IllegalStateException("Evento " + tipo + " non serializzabile", errore);
        }
    }

    /** La busta uguale per tutti gli eventi (docs/eventi.md). */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, Object payload) {
    }

    /**
     * Il payload di ResaRicalcolata, versione 1: il CONTRATTO con Edizioni,
     * che ricalcola la tiratura da kgDisponibili (passo 5).
     * Il campo lottoId (un lotto integrato da Marco) si è aggiunto dopo: è
     * facoltativo, quindi resta la versione 1. Uno tra ordineId e lottoId è null.
     */
    record ResaRicalcolataV1(UUID edizioneId, UUID pianoId, String botanica, BigDecimal kgPrevisti,
                             BigDecimal kgDisponibiliPrima, BigDecimal kgDisponibili, UUID ordineId,
                             UUID lottoId, String motivo) {
    }
}
