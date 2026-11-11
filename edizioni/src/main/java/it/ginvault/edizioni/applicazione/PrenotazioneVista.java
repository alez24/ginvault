package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.CausaAnnullamento;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.Prenotazione;
import it.ginvault.edizioni.dominio.Ritiro;
import it.ginvault.edizioni.dominio.StatoPrenotazione;

/**
 * Quello che l'API restituisce di una prenotazione (JSON):
 *   {"id", "edizione": {"id", "botanicaDiStagione", "origineBotanica", "tiratura", "creataIl"},
 *    "canale", "bottiglie", "prezzoPerBottiglia", "totale", "stato", "prenotataIl", "scadeIl",
 *    "pagamento": {"metodo", "richiestoIl", "riferimento", "confermataIl"},
 *    "numeriBottiglia": [112, 113],
 *    "annullamento": {"causa", "spiegazione", "annullataIl"},
 *    "rimborso": {"richiestoIl", "eseguitoIl", "riferimento"},
 *    "ritiro": {"occasione", "tema", "descrizione", "inizio", "fine", "luogo", "daPortare"}}
 * pagamento è null finché non si chiede di pagare, annullamento finché non è
 * annullata, rimborso se non c'è niente da restituire; numeriBottiglia è
 * vuoto finché non è confermata.
 * Il nome dell'edizione ("Edizione d'estate 2026") lo ricava il front-end
 * dalla data di creazione, come per i piani di Produzione.
 */
public record PrenotazioneVista(UUID id, EdizioneInBreve edizione, Canale canale, int bottiglie,
                                BigDecimal prezzoPerBottiglia, BigDecimal totale, StatoPrenotazione stato,
                                Instant prenotataIl, Instant scadeIl, Pagamento pagamento,
                                List<Integer> numeriBottiglia, Annullamento annullamento, Rimborso rimborso,
                                Ritiro ritiro) {

    /** Dell'edizione, quanto basta per riconoscerla nell'elenco delle prenotazioni. */
    public record EdizioneInBreve(UUID id, String botanicaDiStagione, String origineBotanica, int tiratura,
                                  Instant creataIl) {
    }

    /**
     * La carta scelta e quando; riferimento (pi_..., l'id in Stripe) e
     * confermataIl arrivano con il pagamento riuscito.
     */
    public record Pagamento(String metodo, Instant richiestoIl, String riferimento, Instant confermataIl) {
    }

    /** Perché e quando: carta rifiutata (con la spiegazione di Pagamenti) o tempo finito. */
    public record Annullamento(CausaAnnullamento causa, String spiegazione, Instant annullataIl) {
    }

    /** Un pagamento arrivato a prenotazione annullata: eseguitoIl e riferimento (re_...) quando è restituito. */
    public record Rimborso(Instant richiestoIl, Instant eseguitoIl, String riferimento) {
    }

    static PrenotazioneVista di(Prenotazione prenotazione, Edizione edizione, Ritiro ritiro) {
        EdizioneInBreve inBreve = new EdizioneInBreve(edizione.id(), edizione.botanicaDiStagione(),
                edizione.origineBotanica(), edizione.tiratura().bottiglie(), edizione.creataIl());
        Pagamento pagamento = prenotazione.pagamentoRichiestoIl() == null ? null
                : new Pagamento(prenotazione.metodoPagamento(), prenotazione.pagamentoRichiestoIl(),
                        prenotazione.riferimentoPagamento(), prenotazione.confermataIl());
        Annullamento annullamento = prenotazione.annullataIl() == null ? null
                : new Annullamento(prenotazione.causaAnnullamento(), prenotazione.spiegazioneAnnullamento(),
                        prenotazione.annullataIl());
        Rimborso rimborso = prenotazione.rimborsoRichiestoIl() == null ? null
                : new Rimborso(prenotazione.rimborsoRichiestoIl(), prenotazione.rimborsataIl(),
                        prenotazione.riferimentoRimborso());
        return new PrenotazioneVista(prenotazione.id(), inBreve, prenotazione.canale(), prenotazione.bottiglie(),
                prenotazione.prezzoPerBottiglia(), prenotazione.totale(), prenotazione.stato(),
                prenotazione.prenotataIl(), prenotazione.scadeIl(), pagamento, prenotazione.numeriDiBottiglia(),
                annullamento, rimborso, ritiro);
    }
}
