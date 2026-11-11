package it.ginvault.edizioni.dominio;

/**
 * Per i test fuori dal pacchetto dominio: bottiglie prenotate in una quota
 * senza passare da una prenotazione (es. BAR, che non ha prenotazioni
 * nominali). Edizione.occupa è visibile solo nel pacchetto.
 */
public final class EdizioniDiProva {

    private EdizioniDiProva() {
    }

    public static void occupa(Edizione edizione, Canale canale, int bottiglie) {
        edizione.occupa(canale, bottiglie);
    }
}
