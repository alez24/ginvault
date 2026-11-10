package it.ginvault.magazzino.applicazione;

import java.util.UUID;

/** Nessun lotto con questo id. Diventa un 404. */
public class LottoNonTrovato extends RuntimeException {

    public LottoNonTrovato(UUID id) {
        super("Lotto " + id + " non trovato");
    }
}
