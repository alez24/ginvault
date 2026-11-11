package it.ginvault.edizioni.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: chi ha prenotato ha scelto la carta e chiede di pagare.
 * ScritturaOutbox lo trasforma nel comando RichiestaPagamento su
 * pagamenti.comandi (primo passo della SAGA), con chiave l'id della prenotazione.
 *
 * @param importoCentesimi il totale in centesimi, come lo vuole Stripe: 90,00 euro -> 9000
 * @param metodo           la carta di prova scelta: pm_card_visa o pm_card_chargeDeclined
 * @param descrizione      la riga che compare nella dashboard di Stripe
 */
public record PagamentoRichiesto(UUID prenotazioneId, UUID edizioneId, String acquirente, long importoCentesimi,
                                 String valuta, String metodo, String descrizione, Instant occorsoIl) {
}
