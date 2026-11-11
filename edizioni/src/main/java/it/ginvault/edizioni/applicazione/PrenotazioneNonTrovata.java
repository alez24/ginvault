package it.ginvault.edizioni.applicazione;

import java.util.UUID;

/**
 * Nessuna prenotazione con questo id tra quelle di chi chiede. Diventa un 404:
 * anche la prenotazione di un altro risponde così, per non dire che esiste.
 */
public class PrenotazioneNonTrovata extends RuntimeException {

    public PrenotazioneNonTrovata(UUID id) {
        super("Prenotazione " + id + " non trovata");
    }
}
