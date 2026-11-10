package it.ginvault.produzione.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Corpo di POST /piani/{id}/integrazioni: il lotto proposto da Elena e quanti
 * kg usarne (di solito quelli che mancano: 1,2). Il controllo di forma è qui
 * (400); le regole del piano sono nel dominio (409).
 */
public record NuovaIntegrazione(
        @NotNull UUID lottoId,
        @NotNull @Positive @Digits(integer = 5, fraction = 3) BigDecimal kg) {
}
