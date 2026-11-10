package it.ginvault.produzione.dominio;

/**
 * Dati che violano le regole del piano (kg non positivi, origine mancante...).
 * Arrivano da un evento: riprovare non servirebbe, il messaggio va nel dead
 * letter topic (vedi ConfigurazioneKafka).
 */
public class PianoNonValido extends RuntimeException {

    public PianoNonValido(String messaggio) {
        super(messaggio);
    }
}
