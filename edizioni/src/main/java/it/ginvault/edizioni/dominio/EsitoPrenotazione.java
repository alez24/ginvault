package it.ginvault.edizioni.dominio;

/**
 * Che cosa ha fatto la prenotazione con un passo della SAGA (un esito di
 * Pagamenti o la scadenza). Finisce nei log e nella colonna esito di
 * evento_elaborato.
 */
public enum EsitoPrenotazione {
    /** Pagamento riuscito: confermata, con i numeri di bottiglia. */
    CONFERMATA,
    /** Pagamento rifiutato o tempo scaduto: annullata, bottiglie tornate libere (compensazione). */
    ANNULLATA,
    /** Pagamento riuscito, ma a prenotazione già annullata: si chiede a Pagamenti di restituirlo. */
    RIMBORSO_RICHIESTO,
    /** Pagamenti ha restituito il pagamento arrivato tardi. */
    RIMBORSATA,
    /** Niente da fare: lo stesso esito arrivato di nuovo, o un esito che non cambia più niente. */
    GIA_REGISTRATO
}
