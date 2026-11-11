package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.Prenotazione;
import it.ginvault.edizioni.dominio.Ritiro;
import it.ginvault.edizioni.dominio.StatoPrenotazione;

/**
 * Quello che l'API restituisce di una prenotazione (JSON):
 *   {"id", "edizione": {"id", "botanicaDiStagione", "origineBotanica", "tiratura", "creataIl"},
 *    "canale", "bottiglie", "prezzoPerBottiglia", "totale", "stato", "prenotataIl",
 *    "scadeIl", "ritiro": {"occasione", "tema", "descrizione", "inizio", "fine", "luogo", "daPortare"}}
 * Il nome dell'edizione ("Edizione d'estate 2026") lo ricava il front-end
 * dalla data di creazione, come per i piani di Produzione.
 */
public record PrenotazioneVista(UUID id, EdizioneInBreve edizione, Canale canale, int bottiglie,
                                BigDecimal prezzoPerBottiglia, BigDecimal totale, StatoPrenotazione stato,
                                Instant prenotataIl, Instant scadeIl, Ritiro ritiro) {

    /** Dell'edizione, quanto basta per riconoscerla nell'elenco delle prenotazioni. */
    public record EdizioneInBreve(UUID id, String botanicaDiStagione, String origineBotanica, int tiratura,
                                  Instant creataIl) {
    }

    static PrenotazioneVista di(Prenotazione prenotazione, Edizione edizione, Ritiro ritiro) {
        EdizioneInBreve inBreve = new EdizioneInBreve(edizione.id(), edizione.botanicaDiStagione(),
                edizione.origineBotanica(), edizione.tiratura().bottiglie(), edizione.creataIl());
        return new PrenotazioneVista(prenotazione.id(), inBreve, prenotazione.canale(), prenotazione.bottiglie(),
                prenotazione.prezzoPerBottiglia(), prenotazione.totale(), prenotazione.stato(),
                prenotazione.prenotataIl(), prenotazione.scadeIl(), ritiro);
    }
}
