package it.ginvault.edizioni.applicazione;

import java.util.UUID;

/** Nessuna edizione con questo id. Diventa un 404. */
public class EdizioneNonTrovata extends RuntimeException {

    public EdizioneNonTrovata(UUID id) {
        super("Edizione " + id + " non trovata");
    }
}
