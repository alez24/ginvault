package it.ginvault.pagamenti.dominio;

/**
 * Il provider dei pagamenti non ha dato un esito: rete giù, timeout, errore
 * 5xx, troppe richieste, chiave mancante o sbagliata. Non è un rifiuto della
 * carta: il comando si riprova (ConfigurazioneKafka) e, finiti i tentativi,
 * va nel dead letter topic. La chiave di idempotenza evita doppi addebiti.
 */
public class ProviderNonDisponibile extends RuntimeException {

    public ProviderNonDisponibile(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
