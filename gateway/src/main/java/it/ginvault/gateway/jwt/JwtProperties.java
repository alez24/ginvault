package it.ginvault.gateway.jwt;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametri del JWT interno, da {@code ginvault.jwt.*} in application.yml.
 *
 * @param issuer valore del claim "iss", verificato dai servizi
 * @param durata validità del token (es. 5m); Spring converte "5m" in Duration
 */
@ConfigurationProperties("ginvault.jwt")
public record JwtProperties(String issuer, Duration durata) {
}
