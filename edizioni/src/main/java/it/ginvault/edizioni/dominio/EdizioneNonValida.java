package it.ginvault.edizioni.dominio;

/** Regola di dominio dell'Edizione violata (es. manca la quota di un canale). Diventa un 422. */
public class EdizioneNonValida extends RuntimeException {

    public EdizioneNonValida(String messaggio) {
        super(messaggio);
    }
}
