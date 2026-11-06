package it.ginvault.edizioni.dominio;

/**
 * Ricette non risponde: servizio spento o irraggiungibile, timeout, oppure una
 * risposta inattesa. Diventa un 503 con un messaggio chiaro; la causa tecnica
 * resta solo nei log del servizio.
 */
public class CatalogoRicetteNonDisponibile extends RuntimeException {

    public CatalogoRicetteNonDisponibile(String messaggio) {
        super(messaggio);
    }
}
