package it.ginvault.magazzino.applicazione;

import java.util.UUID;

/** Nessun ordine con questo id. Diventa un 404. */
public class OrdineNonTrovato extends RuntimeException {

    public OrdineNonTrovato(UUID id) {
        super("Ordine " + id + " non trovato");
    }
}
