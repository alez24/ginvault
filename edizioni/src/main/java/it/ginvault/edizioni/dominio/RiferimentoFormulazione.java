package it.ginvault.edizioni.dominio;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value Object: il riferimento a una formulazione di Ricette (id + versione).
 *
 * È TUTTO quello che l'Edizione conserva della formulazione: niente copia
 * della ricetta, delle dosi o dello stato. La formulazione resta di Ricette;
 * Edizioni sa solo "quale" e "quale versione".
 *
 * Colonne formulazione_id e formulazione_versione: su questa coppia c'è il
 * vincolo unique della tabella edizione (vedi Edizione).
 */
@Embeddable
public record RiferimentoFormulazione(
        @Column(name = "formulazione_id", nullable = false, updatable = false)
        UUID id,
        @Column(name = "formulazione_versione", nullable = false, updatable = false)
        int versione) {

    public RiferimentoFormulazione {
        if (id == null) {
            throw new EdizioneNonValida("Serve l'id della formulazione");
        }
        if (versione < 1) {
            throw new EdizioneNonValida("La versione della formulazione parte da 1");
        }
    }
}
