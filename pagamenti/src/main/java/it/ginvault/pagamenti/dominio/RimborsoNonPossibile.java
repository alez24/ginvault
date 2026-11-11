package it.ginvault.pagamenti.dominio;

/**
 * Un rimborso che non si può fare: il pagamento era stato rifiutato (non c'è
 * niente da restituire) o Stripe non accetta il rimborso. Riprovare non
 * cambierebbe niente: il comando va subito nel dead letter topic, da guardare
 * a mano.
 */
public class RimborsoNonPossibile extends RuntimeException {

    public RimborsoNonPossibile(String messaggio) {
        super(messaggio);
    }
}
