package it.ginvault.produzione.dominio;

import java.util.Map;

/**
 * Il piano non accetta il lotto proposto. Diventa un 409 con la spiegazione
 * (GestoreErrori): il campo "motivo" dice al front-end quale regola, i
 * dettagli i valori confrontati.
 */
public class IntegrazioneRifiutata extends RuntimeException {

    public enum Motivo {
        /** Il lotto non è tra quelli utilizzabili della botanica in magazzino */
        LOTTO_NON_DISPONIBILE,
        /** Il lotto è di un'altra botanica */
        ALTRA_BOTANICA,
        /** L'origine del lotto non è quella richiesta dall'edizione */
        ORIGINE_DIVERSA,
        /** Il lotto è già integrato nel piano */
        LOTTO_GIA_INTEGRATO,
        /** Il lotto è arrivato con una consegna del piano: i suoi kg sono già contati */
        LOTTO_DELLA_CONSEGNA,
        /** kg mancanti, a zero o non al grammo */
        KG_NON_VALIDI,
        /** Il piano ha già tutti i kg previsti */
        PIANO_COMPLETO,
        /** Più kg di quelli che mancano al piano */
        KG_ECCESSIVI
    }

    private final Motivo motivo;
    private final Map<String, Object> dettagli;

    public IntegrazioneRifiutata(Motivo motivo, String messaggio) {
        this(motivo, messaggio, Map.of());
    }

    public IntegrazioneRifiutata(Motivo motivo, String messaggio, Map<String, Object> dettagli) {
        super(messaggio);
        this.motivo = motivo;
        this.dettagli = Map.copyOf(dettagli);
    }

    public Motivo motivo() {
        return motivo;
    }

    /** Es. origineRichiesta e origineLotto: finiscono nel corpo della risposta 409. */
    public Map<String, Object> dettagli() {
        return dettagli;
    }
}
