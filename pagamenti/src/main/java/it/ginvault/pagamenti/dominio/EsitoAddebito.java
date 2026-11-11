package it.ginvault.pagamenti.dominio;

/**
 * Come è andato un addebito presso il provider (Stripe). Un rifiuto non è un
 * errore: è un fatto da raccontare a Edizioni, che annullerà la prenotazione.
 * Un provider che non risponde, invece, è un errore (ProviderNonDisponibile)
 * e il comando si riprova.
 */
public sealed interface EsitoAddebito {

    /** @param riferimento l'id del PaymentIntent di Stripe (pi_...) */
    record Riuscito(String riferimento) implements EsitoAddebito {
    }

    /**
     * @param codice      il codice di Stripe, es. "generic_decline" o "resource_missing"
     * @param spiegazione in italiano, per chi legge lo storico o l'avviso
     * @param riferimento l'id del PaymentIntent se Stripe l'ha creato, altrimenti null
     */
    record Rifiutato(MotivoRifiuto motivo, String codice, String spiegazione, String riferimento)
            implements EsitoAddebito {
    }
}
