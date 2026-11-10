package it.ginvault.edizioni.infrastruttura.eventi;

/**
 * Un messaggio che Edizioni non sa leggere: non è JSON, manca un campo
 * obbligatorio della busta o del payload, la versione non è supportata, i kg
 * non sono validi. Riprovare darebbe sempre lo stesso errore: va subito nel
 * dead letter topic (ConfigurazioneKafka), senza bloccare gli eventi dopo di lui.
 */
public class EventoNonValido extends RuntimeException {

    public EventoNonValido(String messaggio) {
        super(messaggio);
    }
}
