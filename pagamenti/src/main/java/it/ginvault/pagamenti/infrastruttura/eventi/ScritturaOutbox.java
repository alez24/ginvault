package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.pagamenti.dominio.MotivoRifiuto;
import it.ginvault.pagamenti.dominio.PagamentoFallito;
import it.ginvault.pagamenti.dominio.PagamentoRiuscito;
import it.ginvault.pagamenti.dominio.RimborsoEseguito;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prima metà della TRANSACTIONAL OUTBOX, come negli altri servizi: trasforma
 * gli eventi di dominio in messaggi (la busta JSON di docs/eventi.md) e li
 * scrive nella tabella outbox.
 *
 * phase = BEFORE_COMMIT: gira dentro la transazione di ElaborazioneComandi,
 * appena prima del commit. Nello stesso commit finiscono il pagamento, l'eventId
 * del comando elaborato e la riga con l'esito: o tutto, o niente.
 */
@Component
public class ScritturaOutbox {

    public static final String TOPIC = "pagamenti.esiti";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ScritturaOutbox(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void pagamentoRiuscito(PagamentoRiuscito evento) {
        scrivi("PagamentoRiuscito", evento.prenotazioneId(), evento.occorsoIl(),
                new PagamentoRiuscitoV1(evento.prenotazioneId(), evento.pagamentoId(), evento.edizioneId(),
                        evento.acquirente(), evento.importoCentesimi(), evento.valuta(), evento.riferimentoStripe()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void pagamentoFallito(PagamentoFallito evento) {
        scrivi("PagamentoFallito", evento.prenotazioneId(), evento.occorsoIl(),
                new PagamentoFallitoV1(evento.prenotazioneId(), evento.pagamentoId(), evento.edizioneId(),
                        evento.acquirente(), evento.motivo(), evento.codice(), evento.spiegazione()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void rimborsoEseguito(RimborsoEseguito evento) {
        scrivi("RimborsoEseguito", evento.prenotazioneId(), evento.occorsoIl(),
                new RimborsoEseguitoV1(evento.prenotazioneId(), evento.pagamentoId(), evento.edizioneId(),
                        evento.acquirente(), evento.importoCentesimi(), evento.valuta(), evento.riferimentoStripe(),
                        evento.motivo()));
    }

    /**
     * Chiave = id della prenotazione: gli esiti della stessa prenotazione vanno
     * nella stessa partizione, in ordine (il rimborso dopo il pagamento).
     */
    private void scrivi(String tipo, UUID prenotazioneId, Instant occorsoIl, Object payload) {
        UUID eventId = UUID.randomUUID();
        Busta busta = new Busta(eventId, tipo, 1, occorsoIl, payload);
        try {
            outbox.save(new RigaOutbox(eventId, TOPIC, prenotazioneId.toString(), tipo,
                    json.writeValueAsString(busta), occorsoIl));
        } catch (JsonProcessingException errore) {
            throw new IllegalStateException("Evento " + tipo + " non serializzabile", errore);
        }
    }

    /** La busta uguale per tutti gli eventi (docs/eventi.md). */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, Object payload) {
    }

    // ---------------------------------------------------------------- i contratti (versione 1)

    /** L'addebito è riuscito: Edizioni conferma la prenotazione e assegna i numeri di bottiglia. */
    record PagamentoRiuscitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                               long importoCentesimi, String valuta, String riferimentoStripe) {
    }

    /** L'addebito non è riuscito: Edizioni annulla la prenotazione e libera le bottiglie. */
    record PagamentoFallitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                              MotivoRifiuto motivo, String codice, String spiegazione) {
    }

    /** L'addebito è stato restituito per intero. */
    record RimborsoEseguitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                              long importoCentesimi, String valuta, String riferimentoStripe, String motivo) {
    }
}
