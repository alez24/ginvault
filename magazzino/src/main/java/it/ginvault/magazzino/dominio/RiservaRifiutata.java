package it.ginvault.magazzino.dominio;

/**
 * Il lotto non accetta la riserva. Il motivo decide lo status gRPC
 * (ServizioGiacenze): chi chiama sa se può riprovare o no.
 */
public class RiservaRifiutata extends RuntimeException {

    public enum Motivo {
        /** Piano mancante, kg non validi -> INVALID_ARGUMENT */
        DATI_NON_VALIDI,
        /** Non conforme al controllo qualità o scaduto -> FAILED_PRECONDITION */
        NON_UTILIZZABILE,
        /** Meno kg liberi di quelli chiesti -> FAILED_PRECONDITION */
        KG_INSUFFICIENTI,
        /** Il piano ha già una riserva su questo lotto, con altri kg -> ALREADY_EXISTS */
        GIA_RISERVATO
    }

    private final Motivo motivo;

    public RiservaRifiutata(Motivo motivo, String messaggio) {
        super(messaggio);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
