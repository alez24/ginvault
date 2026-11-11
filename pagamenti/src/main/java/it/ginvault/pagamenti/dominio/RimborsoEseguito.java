package it.ginvault.pagamenti.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: un addebito riuscito è stato restituito per intero, su
 * richiesta di Edizioni (il pagamento è arrivato quando la prenotazione era
 * già scaduta). Va su pagamenti.esiti.
 *
 * @param riferimentoStripe l'id del rimborso (re_...)
 */
public record RimborsoEseguito(UUID pagamentoId, UUID prenotazioneId, UUID edizioneId, String acquirente,
                               long importoCentesimi, String valuta, String riferimentoStripe, String motivo,
                               Instant occorsoIl) {
}
