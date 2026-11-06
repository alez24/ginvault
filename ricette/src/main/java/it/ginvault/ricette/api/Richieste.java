package it.ginvault.ricette.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import it.ginvault.ricette.dominio.Dose;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Corpi JSON delle richieste. Bean Validation (@Valid nel controller) controlla
 * la FORMA prima di arrivare al dominio: campo mancante, numero negativo... -> 400.
 * Le regole di dominio (es. botanica di stagione mancante in una prova) le
 * controlla l'aggregato -> 422.
 */
public final class Richieste {

    private Richieste() {
    }

    public record DoseRichiesta(
            @NotBlank(message = "la botanica è obbligatoria")
            @Size(max = 50, message = "al massimo 50 caratteri")
            String botanica,
            @NotNull(message = "i grammi per bottiglia sono obbligatori")
            @Positive(message = "i grammi per bottiglia devono essere maggiori di zero")
            @Digits(integer = 5, fraction = 2, message = "al massimo 5 cifre intere e 2 decimali")
            BigDecimal grammiPerBottiglia) {
    }

    /** POST /formulazioni: la formulazione con la sua prima prova. */
    public record NuovaFormulazione(
            @NotBlank(message = "il nome è obbligatorio")
            @Size(max = 100, message = "al massimo 100 caratteri")
            String nome,
            @NotBlank(message = "la botanica di stagione è obbligatoria")
            @Size(max = 50, message = "al massimo 50 caratteri")
            String botanicaDiStagione,
            @NotEmpty(message = "serve almeno una dose")
            List<@NotNull(message = "una dose non può essere vuota") @Valid DoseRichiesta> dosi) {
    }

    /** POST /formulazioni/{id}/versioni: una nuova prova per il panel. */
    public record NuovaVersione(
            @NotEmpty(message = "serve almeno una dose")
            List<@NotNull(message = "una dose non può essere vuota") @Valid DoseRichiesta> dosi) {
    }

    /** POST /formulazioni/{id}/scelta: la versione scelta dal panel. */
    public record Scelta(
            @NotNull(message = "la versione è obbligatoria")
            @Positive(message = "la versione parte da 1")
            Integer versione) {
    }

    /** POST /formulazioni/{id}/utilizzo: l'edizione che usa la formulazione. */
    public record Utilizzo(
            @NotNull(message = "l'id dell'edizione è obbligatorio")
            UUID edizioneId) {
    }

    static List<Dose> comeDosi(List<DoseRichiesta> dosi) {
        return dosi.stream().map(dose -> new Dose(dose.botanica(), dose.grammiPerBottiglia())).toList();
    }
}
