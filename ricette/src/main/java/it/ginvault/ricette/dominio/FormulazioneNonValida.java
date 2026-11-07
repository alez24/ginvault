package it.ginvault.ricette.dominio;

/**
 * I dati violano una regola del dominio (es. una prova senza la botanica di
 * stagione, una versione che non esiste). Via REST diventa 422 Unprocessable
 * Content: la richiesta è ben formata, ma il contenuto non è accettabile.
 */
public class FormulazioneNonValida extends RuntimeException {

    public FormulazioneNonValida(String messaggio) {
        super(messaggio);
    }
}
