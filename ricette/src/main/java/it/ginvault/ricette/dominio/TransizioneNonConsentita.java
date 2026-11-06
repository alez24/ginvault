package it.ginvault.ricette.dominio;

/**
 * L'operazione non è permessa nello stato attuale della formulazione (es.
 * aggiungere una versione dopo la scelta del panel, riusare una formulazione già
 * usata). Via REST diventa 409 Conflict: va in conflitto con lo stato della risorsa.
 */
public class TransizioneNonConsentita extends RuntimeException {

    public TransizioneNonConsentita(String messaggio) {
        super(messaggio);
    }
}
