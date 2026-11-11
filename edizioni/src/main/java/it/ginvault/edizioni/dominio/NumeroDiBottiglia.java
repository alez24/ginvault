package it.ginvault.edizioni.dominio;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value Object: il numero di una bottiglia nell'edizione (Giulia: 112 e 113).
 * Una riga della tabella bottiglia_numerata, con l'edizione: il vincolo
 * UNIQUE (edizione_id, numero) è la rete di sicurezza contro i doppioni,
 * oltre al blocco della riga dell'edizione con cui si assegnano.
 */
@Embeddable
public record NumeroDiBottiglia(
        @Column(name = "edizione_id", nullable = false) UUID edizioneId,
        @Column(name = "numero", nullable = false) int numero) {

    public NumeroDiBottiglia {
        if (edizioneId == null || numero < 1) {
            throw new IllegalArgumentException("Numero di bottiglia non valido: " + numero);
        }
    }
}
