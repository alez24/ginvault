package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.RiferimentoFormulazione;
import it.ginvault.edizioni.dominio.StatoEdizione;

/**
 * Quello che l'API restituisce di un'edizione (JSON):
 *   {"id", "formulazione": {"id", "versione"}, "botanicaDiStagione",
 *    "grammiPerBottiglia", "botanicaDisponibileKg", "origineBotanica", "tiratura",
 *    "quote": {"SOCI", "BAR", "ONLINE"}, "prenotate": {"SOCI", "BAR", "ONLINE"}, "creataIl",
 *    "stato", "prelazioneFinoAl", "tiraturaAnnunciata", "botanicaAnnunciataKg",
 *    "quoteAnnunciate": {"SOCI", "BAR", "ONLINE"}}
 * Si costruisce dall'aggregato; l'aggregato non esce mai dal servizio.
 * Per le edizioni create prima della prelazione, prelazioneFinoAl e i campi
 * dell'annuncio sono null.
 */
public record EdizioneVista(UUID id, RiferimentoFormulazione formulazione, String botanicaDiStagione,
                            BigDecimal grammiPerBottiglia, BigDecimal botanicaDisponibileKg, String origineBotanica,
                            int tiratura,
                            Map<Canale, Integer> quote, Map<Canale, Integer> prenotate, Instant creataIl,
                            StatoEdizione stato, Instant prelazioneFinoAl, Integer tiraturaAnnunciata,
                            BigDecimal botanicaAnnunciataKg, Map<Canale, Integer> quoteAnnunciate) {

    /** La vista dell'edizione adesso: lo stato dipende dall'ora (la prelazione finisce da sola). */
    static EdizioneVista di(Edizione edizione, Instant adesso) {
        Map<Canale, Integer> prenotate = new EnumMap<>(Canale.class);
        edizione.quote().forEach((canale, quota) -> prenotate.put(canale, quota.prenotate()));
        Map<Canale, Integer> annunciate = edizione.quoteAnnunciate();
        return new EdizioneVista(edizione.id(), edizione.formulazione(), edizione.botanicaDiStagione(),
                edizione.grammiPerBottiglia(), edizione.botanicaDisponibileKg(), edizione.origineBotanica(),
                edizione.tiratura().bottiglie(),
                edizione.bottigliePerCanale(), prenotate, edizione.creataIl(),
                edizione.stato(adesso), edizione.prelazioneFinoAl(), edizione.tiraturaAnnunciata(),
                edizione.botanicaAnnunciataKg(), annunciate.isEmpty() ? null : annunciate);
    }
}
