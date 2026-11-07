package it.ginvault.edizioni.dominio;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evento di dominio: la tiratura di un'edizione è scesa (es. la lavanda nuova
 * è arrivata scarsa: da 600 a 540 bottiglie).
 *
 * Lo registra l'aggregato Edizione nel ricalcolo; Spring Data lo pubblica
 * quando il servizio chiama save() (vedi @DomainEvents in Edizione).
 * Per ora resta dentro il processo e viene solo loggato
 * (infrastruttura.eventi.InoltroTiratureRidotte); con la storia di Elena
 * passerà su Kafka verso Notifiche, che avviserà chi ha prenotato.
 *
 * @param quote le quote NUOVE per canale (bottiglie)
 */
public record TiraturaRidotta(UUID edizioneId, int tiraturaVecchia, int tiraturaNuova,
                              Map<Canale, Integer> quote, Instant avvenutaIl) {

    public TiraturaRidotta {
        EnumMap<Canale, Integer> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        quote = Collections.unmodifiableMap(copia);
    }
}
