package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evento di dominio: la tiratura di un'edizione è scesa (es. la lavanda nuova
 * è arrivata scarsa: da 600 a 540 bottiglie). Le bottiglie si tolgono da
 * ONLINE, poi da BAR, mai da SOCI.
 *
 * Lo registra l'aggregato Edizione nel ricalcolo; Spring Data lo pubblica
 * quando il servizio chiama save() (vedi @DomainEvents in Edizione), e
 * ScritturaOutbox lo scrive nella tabella outbox nella stessa transazione: da
 * lì va su edizioni.eventi, dove lo leggerà Notifiche (storia di Giulia) per
 * avvisare chi ha prenotato.
 *
 * @param quote             le quote NUOVE per canale (bottiglie)
 * @param kgDisponibili     i kg su cui è calcolata la tiratura nuova
 * @param causa             in italiano: il motivo della resa di Produzione, o "ricalcolo manuale del distillatore"
 * @param resaRicalcolataId l'eventId della ResaRicalcolata che l'ha causato; null per un ricalcolo manuale
 */
public record TiraturaRidotta(UUID edizioneId, int tiraturaVecchia, int tiraturaNuova, Map<Canale, Integer> quote,
                              BigDecimal kgDisponibili, String causa, UUID resaRicalcolataId, Instant avvenutaIl) {

    public TiraturaRidotta {
        EnumMap<Canale, Integer> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        quote = Collections.unmodifiableMap(copia);
    }
}
