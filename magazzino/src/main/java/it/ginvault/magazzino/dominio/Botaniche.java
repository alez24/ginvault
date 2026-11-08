package it.ginvault.magazzino.dominio;

import java.util.Locale;

/**
 * Nomi di botaniche e origini scritti sempre allo stesso modo, così "Lavanda ",
 * "lavanda" e "LAVANDA" sono la stessa botanica (e la stessa chiave Kafka).
 * Stessa regola di Edizioni: minuscolo, senza spazi ai lati.
 */
public final class Botaniche {

    private Botaniche() {
    }

    /** "  Pino   Mugo " -> "pino mugo". */
    public static String normalizza(String botanica) {
        if (botanica == null || botanica.isBlank()) {
            throw new OrdineNonValido("Serve il nome della botanica");
        }
        return spaziSingoli(botanica).toLowerCase(Locale.ROOT);
    }

    /** Toglie gli spazi ai lati e quelli doppi in mezzo, senza cambiare maiuscole e minuscole. */
    static String spaziSingoli(String testo) {
        return testo.trim().replaceAll("\\s+", " ");
    }
}
