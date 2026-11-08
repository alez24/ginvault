package it.ginvault.magazzino.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.magazzino.dominio.ConsegnaRegistrata;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prima metà della TRANSACTIONAL OUTBOX: trasforma gli eventi di dominio in
 * messaggi (la busta JSON di docs/eventi.md) e li scrive nella tabella outbox.
 *
 * phase = BEFORE_COMMIT: il metodo gira dentro la transazione del servizio,
 * appena prima del commit. La riga di outbox entra nello stesso commit della
 * consegna e del lotto: se il commit fallisce non resta nessuno dei due; se
 * scrivere la riga fallisce, salta tutta la consegna. Non si può avere una
 * consegna senza evento, né un evento senza consegna.
 *
 * Qui NON si parla con Kafka: lo fa PubblicatoreOutbox, fuori dalla richiesta.
 * Per questo la consegna si registra anche con Kafka spento.
 */
@Component
public class ScritturaOutbox {

    public static final String TOPIC = "magazzino.eventi";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ScritturaOutbox(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void consegnaRegistrata(ConsegnaRegistrata evento) {
        ConsegnaRegistrataV1 payload = new ConsegnaRegistrataV1(evento.ordineId(), evento.edizioneId(),
                evento.lottoId(), evento.fornitoreId(), evento.botanica(), evento.origine(), evento.kgOrdinati(),
                evento.kgArrivati(), evento.esitoQualita().name(), evento.consegnataIl());
        scrivi("ConsegnaRegistrata", 1, evento.botanica(), evento.occorsoIl(), payload);
    }

    private void scrivi(String tipo, int versione, String chiave, Instant occorsoIl, Object payload) {
        UUID eventId = UUID.randomUUID();
        Busta busta = new Busta(eventId, tipo, versione, occorsoIl, payload);
        try {
            outbox.save(new RigaOutbox(eventId, TOPIC, chiave, tipo, json.writeValueAsString(busta), occorsoIl));
        } catch (JsonProcessingException errore) {
            throw new IllegalStateException("Evento " + tipo + " non serializzabile", errore);
        }
    }

    /** La busta uguale per tutti gli eventi (docs/eventi.md). */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, Object payload) {
    }

    /**
     * Il payload di ConsegnaRegistrata, versione 1: è il CONTRATTO con
     * Produzione, scritto a parte dal record di dominio. Il dominio può
     * cambiare nomi e campi senza rompere chi legge il topic.
     */
    record ConsegnaRegistrataV1(UUID ordineId, UUID edizioneId, UUID lottoId, UUID fornitoreId, String botanica,
                                String origine, BigDecimal kgOrdinati, BigDecimal kgArrivati, String esitoQualita,
                                LocalDate consegnataIl) {
    }
}
