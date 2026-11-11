package it.ginvault.pagamenti.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: l'addebito è andato a buon fine. ScritturaOutbox lo
 * scrive nell'outbox e da lì va su pagamenti.esiti, con chiave l'id della
 * prenotazione: Edizioni conferma la prenotazione e assegna i numeri di
 * bottiglia (passo 3).
 *
 * @param riferimentoStripe l'id del PaymentIntent (pi_...), da cercare nella dashboard di Stripe
 */
public record PagamentoRiuscito(UUID pagamentoId, UUID prenotazioneId, UUID edizioneId, String acquirente,
                                long importoCentesimi, String valuta, String riferimentoStripe,
                                Instant occorsoIl) {
}
