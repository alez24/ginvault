package it.ginvault.pagamenti.dominio;

import java.util.UUID;

/**
 * Il provider dei pagamenti visto da Pagamenti (una "porta"): oggi Stripe
 * (infrastruttura/stripe/PagamentiStripe), nei test un finto.
 *
 * Ogni chiamata usa una chiave di idempotenza legata alla prenotazione: se la
 * stessa richiesta arriva due volte (un messaggio Kafka riletto dopo un
 * errore), il provider restituisce lo stesso risultato e non addebita due volte.
 */
public interface ProviderDiPagamento {

    /**
     * Addebita l'importo con il metodo di pagamento indicato.
     *
     * @throws ProviderNonDisponibile il provider non risponde o risponde con un errore suo: si riprova
     */
    EsitoAddebito addebita(RichiestaDiPagamento richiesta);

    /**
     * Restituisce per intero un addebito riuscito.
     *
     * @param riferimentoPagamento l'id del PaymentIntent (pi_...)
     * @return l'id del rimborso (re_...)
     * @throws ProviderNonDisponibile il provider non risponde o risponde con un errore suo: si riprova
     */
    String rimborsa(UUID prenotazioneId, String riferimentoPagamento);
}
