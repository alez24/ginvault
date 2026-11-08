package it.ginvault.magazzino.applicazione;

import java.util.UUID;

/** Nessun fornitore con questo id. Diventa un 404. */
public class FornitoreNonTrovato extends RuntimeException {

    public FornitoreNonTrovato(UUID id) {
        super("Fornitore " + id + " non trovato");
    }
}
