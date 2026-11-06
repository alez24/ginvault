package it.ginvault.edizioni.dominio;

/**
 * La formulazione è già stata usata da un'altra edizione: una formulazione
 * usata non si riusa mai. Diventa un 409 (conflitto con lo stato attuale).
 */
public class FormulazioneGiaUsata extends RuntimeException {

    public FormulazioneGiaUsata(String messaggio) {
        super(messaggio);
    }
}
