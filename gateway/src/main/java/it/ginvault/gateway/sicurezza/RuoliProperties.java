package it.ginvault.gateway.sicurezza;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Elenchi di identità per ruolo, letti da {@code ginvault.ruoli.*} in application.yml
 * (a loro volta valorizzati dalle variabili RUOLI_* del file .env).
 * Spring converte da solo una stringa separata da virgole in una List.
 */
@ConfigurationProperties("ginvault.ruoli")
public record RuoliProperties(
        List<String> socio,
        List<String> distillatore,
        List<String> respAcquisti,
        List<String> admin) {
}
