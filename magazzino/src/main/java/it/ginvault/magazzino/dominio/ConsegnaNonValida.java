package it.ginvault.magazzino.dominio;

/**
 * Consegna che non si può registrare così com'è: più kg di quelli ordinati,
 * data nel futuro, scadenza prima della consegna... Diventa un 422.
 * L'ordine resta com'era.
 */
public class ConsegnaNonValida extends RuntimeException {

    public ConsegnaNonValida(String messaggio) {
        super(messaggio);
    }
}
