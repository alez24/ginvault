package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento di dominio: è cambiata la botanica di stagione disponibile per il
 * piano di un'edizione (es. la lavanda arriva scarsa: da 12 a 10,8 kg).
 *
 * Lo registra PianoDiProduzione; ScritturaOutbox lo scrive nella tabella
 * outbox e da lì va su produzione.eventi, con chiave l'id dell'edizione.
 * Edizioni lo leggerà per ricalcolare la tiratura (600 → 540).
 *
 * @param ordineId l'ordine la cui consegna ha cambiato la resa (null per un'integrazione)
 * @param lottoId  il lotto integrato da Marco che ha cambiato la resa (null per una consegna)
 * @param motivo   in italiano, per i log e per chi legge il topic
 */
public record ResaRicalcolata(UUID pianoId, UUID edizioneId, String botanica, BigDecimal kgPrevisti,
                              BigDecimal kgDisponibiliPrima, BigDecimal kgDisponibili, UUID ordineId,
                              UUID lottoId, String motivo, Instant occorsoIl) {
}
