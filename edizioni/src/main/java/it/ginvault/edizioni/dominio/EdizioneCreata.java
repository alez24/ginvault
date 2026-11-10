package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evento di dominio: è nata un'edizione (es. l'edizione d'estate, 12 kg di
 * lavanda dell'Alta Val Varaita a 20 g per bottiglia: 600 bottiglie).
 *
 * Lo registra Edizione.nuova(); Spring Data lo pubblica al salvataggio e
 * ScritturaOutbox lo scrive nella tabella outbox, nella stessa transazione.
 * Da lì va su edizioni.eventi: Produzione lo legge e prepara il piano di
 * produzione dell'edizione.
 *
 * @param kgPrevisti i kg di botanica di stagione su cui è calcolata la tiratura
 * @param quote      bottiglie per canale
 */
public record EdizioneCreata(UUID edizioneId, RiferimentoFormulazione formulazione, String botanicaDiStagione,
                             BigDecimal grammiPerBottiglia, String origineBotanica, BigDecimal kgPrevisti,
                             int tiratura, Map<Canale, Integer> quote, Instant occorsoIl) {

    public EdizioneCreata {
        EnumMap<Canale, Integer> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        quote = Collections.unmodifiableMap(copia);
    }
}
