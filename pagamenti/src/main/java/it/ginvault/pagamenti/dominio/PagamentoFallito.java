package it.ginvault.pagamenti.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: l'addebito non è andato a buon fine (carta rifiutata,
 * verifica richiesta, richiesta non valida). Va su pagamenti.esiti: Edizioni
 * annulla la prenotazione e libera le bottiglie (passo 3).
 *
 * @param codice      il codice di Stripe, es. "generic_decline"
 * @param spiegazione in italiano, per l'avviso a chi ha prenotato
 */
public record PagamentoFallito(UUID pagamentoId, UUID prenotazioneId, UUID edizioneId, String acquirente,
                               MotivoRifiuto motivo, String codice, String spiegazione, Instant occorsoIl) {
}
