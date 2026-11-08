package it.ginvault.magazzino.dominio;

/** Regola dell'ordine di acquisto violata (kg non validi, fornitore inesistente...). Diventa un 422. */
public class OrdineNonValido extends RuntimeException {

    public OrdineNonValido(String messaggio) {
        super(messaggio);
    }
}
