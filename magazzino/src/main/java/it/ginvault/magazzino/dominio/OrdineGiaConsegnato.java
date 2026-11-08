package it.ginvault.magazzino.dominio;

/** Una seconda consegna per lo stesso ordine: ogni ordine ne ha una sola. Diventa un 409. */
public class OrdineGiaConsegnato extends RuntimeException {

    public OrdineGiaConsegnato(String messaggio) {
        super(messaggio);
    }
}
