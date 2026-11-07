package it.ginvault.ricette.applicazione;

import java.util.UUID;

/** Nessuna formulazione con questo id. Via REST diventa 404 Not Found. */
public class FormulazioneNonTrovata extends RuntimeException {

    public FormulazioneNonTrovata(UUID id) {
        super("Formulazione " + id + " non trovata");
    }
}
