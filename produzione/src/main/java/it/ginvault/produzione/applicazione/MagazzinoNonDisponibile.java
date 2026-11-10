package it.ginvault.produzione.applicazione;

/**
 * Il magazzino non ha risposto entro la deadline (DEADLINE_EXCEEDED) o non è
 * raggiungibile (UNAVAILABLE). Diventa un 503: si può riprovare fra poco, e
 * Giacenze.RiservaLotto è idempotente, quindi riprovare non riserva due volte.
 */
public class MagazzinoNonDisponibile extends RuntimeException {

    public MagazzinoNonDisponibile(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
