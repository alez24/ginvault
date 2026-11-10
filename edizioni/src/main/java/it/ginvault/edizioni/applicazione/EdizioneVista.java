package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.RiferimentoFormulazione;

/**
 * Quello che l'API restituisce di un'edizione (JSON):
 *   {"id", "formulazione": {"id", "versione"}, "botanicaDiStagione",
 *    "grammiPerBottiglia", "botanicaDisponibileKg", "origineBotanica", "tiratura",
 *    "quote": {"SOCI", "BAR", "ONLINE"}, "prenotate": {"SOCI", "BAR", "ONLINE"}, "creataIl"}
 * Si costruisce dall'aggregato; l'aggregato non esce mai dal servizio.
 */
public record EdizioneVista(UUID id, RiferimentoFormulazione formulazione, String botanicaDiStagione,
                            BigDecimal grammiPerBottiglia, BigDecimal botanicaDisponibileKg, String origineBotanica,
                            int tiratura,
                            Map<Canale, Integer> quote, Map<Canale, Integer> prenotate, Instant creataIl) {

    static EdizioneVista di(Edizione edizione) {
        Map<Canale, Integer> prenotate = new EnumMap<>(Canale.class);
        edizione.quote().forEach((canale, quota) -> prenotate.put(canale, quota.prenotate()));
        return new EdizioneVista(edizione.id(), edizione.formulazione(), edizione.botanicaDiStagione(),
                edizione.grammiPerBottiglia(), edizione.botanicaDisponibileKg(), edizione.origineBotanica(),
                edizione.tiratura().bottiglie(),
                edizione.bottigliePerCanale(), prenotate, edizione.creataIl());
    }
}
