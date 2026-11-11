package it.ginvault.edizioni.dominio;

/** Perché una prenotazione è stata annullata (e le sue bottiglie sono tornate libere). */
public enum CausaAnnullamento {
    /** Pagamenti ha risposto PagamentoFallito: carta rifiutata, verifica richiesta, richiesta non valida. */
    PAGAMENTO_RIFIUTATO,
    /** Il tempo per pagare è finito prima che arrivasse un pagamento riuscito. */
    SCADUTA
}
