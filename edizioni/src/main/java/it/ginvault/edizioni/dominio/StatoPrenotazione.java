package it.ginvault.edizioni.dominio;

/**
 * Gli stati di una prenotazione (docs/piano.md, state machine).
 *
 *   IN_ATTESA_PAGAMENTO --(pagamento riuscito)--> CONFERMATA, con i numeri di bottiglia
 *   IN_ATTESA_PAGAMENTO --(pagamento rifiutato o tempo finito)--> ANNULLATA
 *
 * Le transizioni le fa la SAGA con Pagamenti: Edizioni chiede il pagamento
 * e, se non va, annulla la prenotazione e rilascia le bottiglie
 * (compensazione). Un pagamento riuscito arrivato a prenotazione annullata
 * non la riapre: si chiede il rimborso (Prenotazione.rimborsoRichiestoIl).
 */
public enum StatoPrenotazione {
    /** Appena fatta: le bottiglie sono già tolte dalla quota, si aspetta il pagamento. */
    IN_ATTESA_PAGAMENTO,
    /** Pagata: le bottiglie sono del socio o del cliente. */
    CONFERMATA,
    /** Non pagata: le bottiglie sono tornate libere nella quota. */
    ANNULLATA;

    /** Le prenotazioni che tengono occupate bottiglie della quota (e contano nel limite per persona). */
    public boolean occupaBottiglie() {
        return this != ANNULLATA;
    }
}
