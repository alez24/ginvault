package it.ginvault.produzione.dominio;

import java.util.Locale;

/**
 * Confronto di origini e botaniche. Arrivano da due servizi diversi (Edizioni
 * le scrive per l'edizione, Magazzino per il fornitore e l'ordine): contano le
 * parole, non maiuscole e spazi.
 *   "Alta Val Varaita (Alpi Cozie)" = "alta val  varaita (alpi cozie)"
 *   "Alta Val Varaita (Alpi Cozie)" ≠ "Campi GinVault (Saluzzo, 400 m)"
 */
public final class Origini {

    private Origini() {
    }

    public static boolean stessa(String una, String altra) {
        return una != null && altra != null && chiave(una).equals(chiave(altra));
    }

    /** Spazi singoli, senza spazi ai lati: per salvare e mostrare. */
    static String pulisci(String testo) {
        return testo.trim().replaceAll("\\s+", " ");
    }

    /** "  Pino   Mugo " -> "pino mugo": come Edizioni e Magazzino scrivono le botaniche. */
    static String botanica(String botanica) {
        return chiave(botanica);
    }

    private static String chiave(String testo) {
        return pulisci(testo).toLowerCase(Locale.ROOT);
    }
}
