package it.ginvault.edizioni.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Corpi JSON delle richieste. Bean Validation (@Valid nel controller) controlla
 * la FORMA prima di arrivare al dominio: campo mancante, numero negativo... -> 400.
 * Le regole di dominio (es. formulazione non scelta, quota mancante per un
 * canale) le controllano dominio e Ricette -> 422 / 409.
 */
public final class Richieste {

    private Richieste() {
    }

    /**
     * POST /edizioni, ad esempio:
     * <pre>
     * {"formulazione": {"id": "7f1d...", "versione": 2},
     *  "botanicaDisponibileKg": 12,
     *  "quote": {"SOCI": 300, "BAR": 150, "ONLINE": 150}}
     * </pre>
     * Un canale sconosciuto nelle quote (es. "NEGOZIO") -> 400, JSON non leggibile.
     */
    public record NuovaEdizione(
            @NotNull(message = "serve la formulazione (id e versione)")
            @Valid
            Formulazione formulazione,
            @NotNull(message = "i kg di botanica disponibili sono obbligatori")
            @Positive(message = "i kg di botanica disponibili devono essere maggiori di zero")
            @Digits(integer = 5, fraction = 3, message = "al massimo 5 cifre intere e 3 decimali (il grammo)")
            BigDecimal botanicaDisponibileKg,
            @NotEmpty(message = "servono le quote per canale (SOCI, BAR, ONLINE)")
            Map<Canale, @NotNull(message = "la quota è obbligatoria")
                        @PositiveOrZero(message = "la quota non può essere negativa") Integer> quote) {
    }

    /** POST /edizioni/{id}/ricalcolo: la nuova disponibilità, es. {"botanicaDisponibileKg": 10.8}. */
    public record Ricalcolo(
            @NotNull(message = "i kg di botanica disponibili sono obbligatori")
            @Positive(message = "i kg di botanica disponibili devono essere maggiori di zero")
            @Digits(integer = 5, fraction = 3, message = "al massimo 5 cifre intere e 3 decimali (il grammo)")
            BigDecimal botanicaDisponibileKg) {
    }

    /** Il riferimento alla formulazione di Ricette. */
    public record Formulazione(
            @NotNull(message = "l'id della formulazione è obbligatorio")
            UUID id,
            @NotNull(message = "la versione della formulazione è obbligatoria")
            @Positive(message = "la versione parte da 1")
            Integer versione) {
    }
}
