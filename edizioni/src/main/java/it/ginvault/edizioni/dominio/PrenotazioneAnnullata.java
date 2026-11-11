package it.ginvault.edizioni.dominio;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: la prenotazione è annullata e le sue bottiglie sono
 * tornate libere nella quota (la COMPENSAZIONE della SAGA). Va su
 * edizioni.eventi.
 *
 * @param spiegazione in italiano, per chi ha prenotato: il motivo del rifiuto o l'ora della scadenza
 */
public record PrenotazioneAnnullata(UUID prenotazioneId, UUID edizioneId, String acquirente, Canale canale,
                                    int bottiglie, CausaAnnullamento causa, String spiegazione,
                                    Instant occorsoIl) {
}
