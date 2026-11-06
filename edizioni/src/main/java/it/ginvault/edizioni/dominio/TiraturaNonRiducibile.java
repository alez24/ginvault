package it.ginvault.edizioni.dominio;

/**
 * Il ricalcolo abbasserebbe la tiratura più di quanto le quote permettono:
 * bisognerebbe toccare la quota SOCI, o scendere sotto le bottiglie già
 * prenotate. Diventa un 409 con la spiegazione; l'edizione resta com'era.
 */
public class TiraturaNonRiducibile extends RuntimeException {

    public TiraturaNonRiducibile(String spiegazione) {
        super(spiegazione);
    }
}
