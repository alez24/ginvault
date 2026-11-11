package it.ginvault.edizioni.dominio;

/**
 * Una prenotazione che le regole di vendita non permettono adesso. Diventa un
 * 409 con il motivo (per il front-end) e la spiegazione (per la persona).
 * Le bottiglie finite sono un caso a parte: QuotaEsaurita.
 */
public class PrenotazioneNonPossibile extends RuntimeException {

    public enum Motivo {
        /** L'edizione è in prelazione e chi prenota non è socio. */
        PRELAZIONE_DEI_SOCI,
        /** Con questa prenotazione la persona supererebbe il massimo di bottiglie per edizione. */
        LIMITE_PER_PERSONA
    }

    private final Motivo motivo;

    public PrenotazioneNonPossibile(Motivo motivo, String messaggio) {
        super(messaggio);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
