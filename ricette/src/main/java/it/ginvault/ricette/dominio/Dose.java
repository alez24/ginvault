package it.ginvault.ricette.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value Object: quanti grammi di una botanica servono per UNA bottiglia.
 *
 * È un record: immutabile e confrontato per valore (due dosi con la stessa
 * botanica e gli stessi grammi sono la stessa dose). Non ha un id: vive solo
 * dentro una {@link ProvaDiAssaggio}.
 *
 * Il costruttore garantisce che una Dose sia sempre valida: nome della botanica
 * normalizzato (minuscolo, senza spazi ai bordi, così "Lavanda " e "lavanda"
 * sono la stessa botanica) e grammi positivi con al massimo due decimali.
 */
@Embeddable
public record Dose(
        @Column(name = "botanica", nullable = false, length = 50)
        String botanica,
        @Column(name = "grammi_per_bottiglia", nullable = false, precision = 7, scale = 2)
        BigDecimal grammiPerBottiglia) {

    public Dose {
        botanica = normalizzaBotanica(botanica);
        if (botanica.isEmpty()) {
            throw new FormulazioneNonValida("La botanica di una dose è obbligatoria");
        }
        if (grammiPerBottiglia == null || grammiPerBottiglia.signum() <= 0) {
            throw new FormulazioneNonValida(
                    "I grammi per bottiglia di " + botanica + " devono essere maggiori di zero");
        }
        if (grammiPerBottiglia.stripTrailingZeros().scale() > 2) {
            throw new FormulazioneNonValida(
                    "I grammi per bottiglia di " + botanica + " hanno al massimo due decimali");
        }
        // Scala fissa a 2 decimali: 20 e 20.0 diventano la stessa dose (20.00)
        grammiPerBottiglia = grammiPerBottiglia.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Stessa normalizzazione per le dosi e per la botanica di stagione. */
    public static String normalizzaBotanica(String botanica) {
        return botanica == null ? "" : botanica.trim().toLowerCase(Locale.ROOT);
    }
}
