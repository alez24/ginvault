package it.ginvault.edizioni.dominio;

/**
 * Com'è andata l'applicazione di una resa (Edizione.applicaResa).
 *
 * @param tiraturaPrima la tiratura prima del ricalcolo
 * @param tiratura      quella dopo: uguale a tiraturaPrima se RIFIUTATO o INVARIATA
 * @param spiegazione   perché il ricalcolo è rifiutato; null negli altri casi
 */
public record EsitoRicalcolo(Tipo tipo, int tiraturaPrima, int tiratura, String spiegazione) {

    public enum Tipo {
        /** La tiratura è scesa: evento TiraturaRidotta. */
        RIDOTTA,
        /** La tiratura è salita: evento TiraturaAumentata. */
        AUMENTATA,
        /** Stessa tiratura (es. 12 kg -> 13 kg, sempre 600): cambiano solo i kg, nessun evento. */
        INVARIATA,
        /** Il ricalcolo non si può fare: l'edizione resta com'era, evento RicalcoloRifiutato. */
        RIFIUTATO
    }
}
