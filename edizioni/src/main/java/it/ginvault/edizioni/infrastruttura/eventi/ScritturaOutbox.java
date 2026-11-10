package it.ginvault.edizioni.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.EdizioneCreata;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prima metà della TRANSACTIONAL OUTBOX (come in Magazzino): trasforma gli
 * eventi di dominio di Edizioni in messaggi (la busta JSON di docs/eventi.md)
 * e li scrive nella tabella outbox, dentro la transazione del salvataggio
 * (phase = BEFORE_COMMIT). Edizione e riga di outbox entrano nello stesso
 * commit: o ci sono entrambe, o nessuna.
 *
 * La chiave del messaggio è l'id dell'edizione: tutti gli eventi di
 * un'edizione finiscono nella stessa partizione, nell'ordine in cui nascono.
 *
 * TiraturaRidotta per ora resta nel log (InoltroTiratureRidotte): passerà da
 * qui quando Edizioni consumerà ResaRicalcolata.
 */
@Component
public class ScritturaOutbox {

    public static final String TOPIC = "edizioni.eventi";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ScritturaOutbox(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void edizioneCreata(EdizioneCreata evento) {
        EdizioneCreataV1 payload = new EdizioneCreataV1(evento.edizioneId(),
                new FormulazioneV1(evento.formulazione().id(), evento.formulazione().versione()),
                evento.botanicaDiStagione(), evento.grammiPerBottiglia(), evento.origineBotanica(),
                evento.kgPrevisti(), evento.tiratura(), evento.quote());
        scrivi("EdizioneCreata", 1, evento.edizioneId(), evento.occorsoIl(), payload);
    }

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
     * Il payload di EdizioneCreata, versione 1: il CONTRATTO con Produzione (e
     * più avanti Notifiche), separato dal record di dominio.
     *
     * @param kgPrevisti i kg di botanica di stagione su cui è calcolata la tiratura
     */
    record EdizioneCreataV1(UUID edizioneId, FormulazioneV1 formulazione, String botanicaDiStagione,
                            BigDecimal grammiPerBottiglia, String origineBotanica, BigDecimal kgPrevisti,
                            int tiratura, Map<Canale, Integer> quote) {
    }

    record FormulazioneV1(UUID id, int versione) {
    }
}
