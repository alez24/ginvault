package it.ginvault.edizioni.dominio;

/**
 * Si chiedono più bottiglie di quelle libere in un canale. Diventa un 409.
 * Oggi la usa solo Edizione.prenota(); l'endpoint delle prenotazioni arriverà
 * con la storia delle prenotazioni.
 */
public class QuotaEsaurita extends RuntimeException {

    public QuotaEsaurita(String messaggio) {
        super(messaggio);
    }
}
