package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evento di dominio: la tiratura di un'edizione è salita (es. Marco integra un
 * lotto dell'Alta Val Varaita e la resa torna a 12 kg: da 540 a 600
 * bottiglie). Le bottiglie in più sono andate tutte a ONLINE, mai oltre 600.
 *
 * Come TiraturaRidotta, va su edizioni.eventi passando dall'outbox.
 *
 * @param quote             le quote NUOVE per canale (bottiglie)
 * @param kgDisponibili     i kg su cui è calcolata la tiratura nuova
 * @param causa             in italiano: il motivo della resa di Produzione, o "ricalcolo manuale del distillatore"
 * @param resaRicalcolataId l'eventId della ResaRicalcolata che l'ha causato; null per un ricalcolo manuale
 */
public record TiraturaAumentata(UUID edizioneId, int tiraturaVecchia, int tiraturaNuova, Map<Canale, Integer> quote,
                                BigDecimal kgDisponibili, String causa, UUID resaRicalcolataId, Instant avvenutaIl) {

    public TiraturaAumentata {
        EnumMap<Canale, Integer> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        quote = Collections.unmodifiableMap(copia);
    }
}
