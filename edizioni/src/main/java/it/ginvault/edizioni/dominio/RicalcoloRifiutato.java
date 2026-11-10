package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Evento di dominio: Produzione ha ricalcolato la resa, ma l'edizione non può
 * seguirla. Le quote andrebbero sotto le bottiglie già prenotate o
 * toccherebbero SOCI, oppure con quei kg non si riempie nemmeno una bottiglia.
 * L'edizione resta com'era. L'evento va su edizioni.eventi (outbox), così il
 * rifiuto non si perde: qualcuno deve decidere cosa fare.
 *
 * @param tiratura          la tiratura dell'edizione, rimasta com'era
 * @param tiraturaRichiesta la tiratura che verrebbe con i kg nuovi (0: nemmeno una bottiglia)
 * @param kgDisponibili     i kg dell'edizione, rimasti com'erano
 * @param kgRichiesti       i kg della resa di Produzione
 * @param quote             le quote dell'edizione, con le prenotate: il perché del rifiuto
 * @param spiegazione       in italiano, la stessa del 409 del ricalcolo manuale
 * @param causa             il motivo della resa di Produzione (es. "consegna ridotta: 5 su 12 kg")
 * @param resaRicalcolataId l'eventId della ResaRicalcolata rifiutata
 */
public record RicalcoloRifiutato(UUID edizioneId, Motivo motivo, int tiratura, int tiraturaRichiesta,
                                 BigDecimal kgDisponibili, BigDecimal kgRichiesti, Map<Canale, Quota> quote,
                                 String spiegazione, String causa, UUID resaRicalcolataId, Instant avvenutaIl) {

    public enum Motivo {
        /** Bisognerebbe togliere da SOCI, o scendere sotto le bottiglie già prenotate. */
        QUOTE_NON_RIDUCIBILI,
        /** Con i kg nuovi non si riempie nemmeno una bottiglia (es. 0 kg utilizzabili). */
        BOTANICA_INSUFFICIENTE
    }

    public RicalcoloRifiutato {
        EnumMap<Canale, Quota> copia = new EnumMap<>(Canale.class);
        copia.putAll(quote);
        quote = Collections.unmodifiableMap(copia);
    }
}
