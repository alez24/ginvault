package it.ginvault.produzione.applicazione;

/**
 * Il magazzino ha risposto con un errore che non dipende da Marco (INTERNAL,
 * UNAUTHENTICATED, UNIMPLEMENTED...). Diventa un 502 Bad Gateway: il problema
 * è nel servizio chiamato o nella configurazione tra i due, non nella richiesta.
 */
public class ErroreMagazzino extends RuntimeException {

    public ErroreMagazzino(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
