package it.ginvault.magazzino.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import it.ginvault.magazzino.dominio.DatiConsegna;
import it.ginvault.magazzino.dominio.EsitoQualita;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Corpi JSON delle richieste. Bean Validation (@Valid nel controller) controlla
 * la FORMA: campo mancante, numero negativo, testo troppo lungo -> 400.
 * Le regole di dominio (più kg di quelli ordinati, consegna nel futuro,
 * ordine già consegnato) le controlla OrdineDiAcquisto -> 422 / 409.
 */
public final class Richieste {

    private Richieste() {
    }

    /**
     * POST /ordini, ad esempio:
     * <pre>
     * {"fornitoreId": "f0000000-0000-4000-8000-000000000001", "botanica": "lavanda",
     *  "kgOrdinati": 12, "consegnaAttesa": "2026-07-31", "edizioneId": "..."}
     * </pre>
     */
    public record NuovoOrdine(
            @NotNull(message = "serve il fornitore")
            UUID fornitoreId,
            @NotBlank(message = "serve la botanica")
            @Size(max = 50, message = "al massimo 50 caratteri")
            String botanica,
            @NotNull(message = "i kg ordinati sono obbligatori")
            @Positive(message = "i kg ordinati devono essere maggiori di zero")
            @Digits(integer = 5, fraction = 3, message = "al massimo 5 cifre intere e 3 decimali (il grammo)")
            BigDecimal kgOrdinati,
            @NotNull(message = "serve la data di consegna attesa")
            LocalDate consegnaAttesa,
            @NotNull(message = "serve l'edizione per cui si ordina")
            UUID edizioneId) {
    }

    /**
     * POST /ordini/{id}/consegne, ad esempio:
     * <pre>
     * {"kgArrivati": 10.8, "consegnataIl": "2026-08-06", "scadenza": "2027-08-31",
     *  "esitoQualita": "CONFORME", "nota": "pioggia durante la fioritura"}
     * </pre>
     * Un esito diverso da CONFORME / NON_CONFORME -> 400, JSON non leggibile.
     */
    public record NuovaConsegna(
            @NotNull(message = "i kg arrivati sono obbligatori")
            @Positive(message = "i kg arrivati devono essere maggiori di zero")
            @Digits(integer = 5, fraction = 3, message = "al massimo 5 cifre intere e 3 decimali (il grammo)")
            BigDecimal kgArrivati,
            @NotNull(message = "serve la data di consegna")
            LocalDate consegnataIl,
            @NotNull(message = "serve la scadenza del lotto")
            LocalDate scadenza,
            @NotNull(message = "serve l'esito del controllo qualità (CONFORME o NON_CONFORME)")
            EsitoQualita esitoQualita,
            @Size(max = 500, message = "al massimo 500 caratteri")
            String nota) {

        DatiConsegna dati() {
            return new DatiConsegna(kgArrivati, consegnataIl, scadenza, esitoQualita, nota);
        }
    }
}
