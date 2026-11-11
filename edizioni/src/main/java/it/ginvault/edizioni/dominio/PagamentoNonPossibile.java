package it.ginvault.edizioni.dominio;

/**
 * Un pagamento che adesso non si può chiedere. Diventa un 409 con il motivo
 * (per il front-end) e la spiegazione (per la persona).
 */
public class PagamentoNonPossibile extends RuntimeException {

    public enum Motivo {
        /** Il pagamento di questa prenotazione è già stato chiesto: si aspetta l'esito. */
        PAGAMENTO_GIA_RICHIESTO,
        /** Il tempo per pagare è finito: la prenotazione si annulla e le bottiglie tornano libere. */
        PRENOTAZIONE_SCADUTA,
        /** La prenotazione è già confermata o annullata. */
        PRENOTAZIONE_CHIUSA
    }

    private final Motivo motivo;

    public PagamentoNonPossibile(Motivo motivo, String messaggio) {
        super(messaggio);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
