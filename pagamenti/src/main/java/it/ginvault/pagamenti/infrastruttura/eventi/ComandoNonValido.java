package it.ginvault.pagamenti.infrastruttura.eventi;

/**
 * Un comando che Pagamenti non sa leggere: non è JSON, manca un campo
 * obbligatorio, la versione non è supportata, il tipo è sconosciuto, un valore
 * non va bene (importo zero, metodo di pagamento che non è un "pm_...").
 * Riprovare darebbe sempre lo stesso errore: va subito nel dead letter topic
 * (ConfigurazioneKafka), senza bloccare i comandi dopo di lui.
 *
 * A differenza di Produzione, che ignora gli eventi che non le servono, qui un
 * tipo sconosciuto è un errore: su pagamenti.comandi arrivano solo comandi per
 * Pagamenti.
 */
public class ComandoNonValido extends RuntimeException {

    public ComandoNonValido(String messaggio) {
        super(messaggio);
    }
}
