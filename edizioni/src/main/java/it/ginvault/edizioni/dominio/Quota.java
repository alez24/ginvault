package it.ginvault.edizioni.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value Object: le bottiglie di un canale e quante di queste sono già prenotate.
 * Invariante: 0 <= prenotate <= bottiglie. Le "libere" sono quelle che si
 * possono ancora prenotare, o togliere se la tiratura scende.
 *
 * Immutabile: ogni modifica restituisce una nuova Quota.
 */
@Embeddable
public record Quota(
        @Column(name = "bottiglie", nullable = false) int bottiglie,
        @Column(name = "prenotate", nullable = false) int prenotate) {

    public Quota {
        // Guardia interna: le regole con messaggi per l'utente le controlla
        // Edizione prima di arrivare qui.
        if (bottiglie < 0 || prenotate < 0 || prenotate > bottiglie) {
            throw new IllegalArgumentException("Quota impossibile: " + prenotate + " prenotate su " + bottiglie);
        }
    }

    /** Una quota nuova: nessuna bottiglia ancora prenotata. */
    public static Quota di(int bottiglie) {
        return new Quota(bottiglie, 0);
    }

    public int libere() {
        return bottiglie - prenotate;
    }

    public Quota conBottiglie(int nuove) {
        return new Quota(nuove, prenotate);
    }

    public Quota conPrenotate(int nuove) {
        return new Quota(bottiglie, nuove);
    }
}
