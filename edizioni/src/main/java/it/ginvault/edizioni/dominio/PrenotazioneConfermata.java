package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evento di dominio: il pagamento è riuscito e la prenotazione è confermata,
 * con i suoi numeri di bottiglia (Giulia: 112 e 113). Va su edizioni.eventi:
 * Notifiche lo dirà a chi ha prenotato.
 *
 * @param numeri i numeri di bottiglia, progressivi nell'edizione, in ordine
 */
public record PrenotazioneConfermata(UUID prenotazioneId, UUID edizioneId, String acquirente, Canale canale,
                                     int bottiglie, List<Integer> numeri, BigDecimal totale, UUID pagamentoId,
                                     Instant occorsoIl) {

    public PrenotazioneConfermata {
        numeri = List.copyOf(numeri);
    }
}
