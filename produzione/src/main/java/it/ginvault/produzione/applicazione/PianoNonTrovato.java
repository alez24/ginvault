package it.ginvault.produzione.applicazione;

/**
 * Nessun piano per quell'edizione o con quell'id.
 *  - Dall'API: 404.
 *  - Da una ConsegnaRegistrata: forse l'EdizioneCreata non è ancora arrivata
 *    (i due topic non hanno un ordine tra loro). Il consumer riprova con
 *    attese crescenti; se il piano non compare, il messaggio va nel dead
 *    letter topic.
 */
public class PianoNonTrovato extends RuntimeException {

    public PianoNonTrovato(String messaggio) {
        super(messaggio);
    }
}
