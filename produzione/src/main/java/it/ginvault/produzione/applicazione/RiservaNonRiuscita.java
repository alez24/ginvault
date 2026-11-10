package it.ginvault.produzione.applicazione;

/**
 * Il magazzino ha rifiutato la riserva per una sua regola: kg insufficienti,
 * lotto non utilizzabile o sparito, altra riserva dello stesso piano, riserva
 * concorrente. Diventa un 409 con la spiegazione del magazzino.
 *
 * @see it.ginvault.produzione.infrastruttura.magazzino.ClientGiacenze per gli status gRPC
 */
public class RiservaNonRiuscita extends RuntimeException {

    private final String statoGrpc;

    public RiservaNonRiuscita(String statoGrpc, String messaggio) {
        super(messaggio);
        this.statoGrpc = statoGrpc;
    }

    /** Es. "FAILED_PRECONDITION": va nel corpo della risposta. */
    public String statoGrpc() {
        return statoGrpc;
    }
}
