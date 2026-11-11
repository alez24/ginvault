package it.ginvault.edizioni.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: un pagamento è riuscito quando la prenotazione era già
 * annullata (per esempio scaduta mentre Pagamenti era fermo). Le bottiglie
 * non ci sono più, quindi il pagamento va restituito. ScritturaOutbox lo
 * trasforma nel comando RichiestaRimborso su pagamenti.comandi.
 */
public record RimborsoRichiesto(UUID prenotazioneId, UUID edizioneId, String motivo, Instant occorsoIl) {
}
