package it.ginvault.pagamenti.dominio;

/**
 * Gli stati di un pagamento.
 *
 *   (addebito riuscito) -> RIUSCITO --(rimborso)--> RIMBORSATO
 *   (addebito rifiutato) -> RIFIUTATO
 *
 * Non c'è uno stato "in corso": il pagamento si salva solo con l'esito di
 * Stripe in mano.
 */
public enum StatoPagamento {
    RIUSCITO,
    RIFIUTATO,
    /** Restituito: Edizioni l'ha chiesto perché il pagamento è arrivato dopo la scadenza della prenotazione. */
    RIMBORSATO
}
