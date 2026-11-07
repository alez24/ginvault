package it.ginvault.edizioni.dominio;

/**
 * La formulazione indicata non può diventare un'edizione: non esiste in
 * Ricette, il panel non l'ha ancora scelta, oppure la versione indicata non è
 * quella scelta. Diventa un 422: la richiesta è ben formata, ma il suo
 * contenuto non è accettabile.
 */
public class FormulazioneNonUtilizzabile extends RuntimeException {

    public FormulazioneNonUtilizzabile(String messaggio) {
        super(messaggio);
    }
}
