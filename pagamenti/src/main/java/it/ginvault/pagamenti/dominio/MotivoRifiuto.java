package it.ginvault.pagamenti.dominio;

/** Perché un pagamento non è andato a buon fine. Va in PagamentoFallito: Edizioni e Notifiche lo leggono. */
public enum MotivoRifiuto {
    /** La banca ha rifiutato la carta (con pm_card_chargeDeclined: "generic_decline"). */
    CARTA_RIFIUTATA,
    /** La carta chiede una verifica (3D Secure) che un pagamento dal server non può fare. */
    AUTENTICAZIONE_RICHIESTA,
    /** Stripe non accetta la richiesta, per esempio un metodo di pagamento che non esiste. */
    RICHIESTA_NON_VALIDA
}
