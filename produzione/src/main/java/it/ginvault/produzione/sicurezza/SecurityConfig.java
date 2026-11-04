package it.ginvault.produzione.sicurezza;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Il servizio è un "resource server" OAuth2: non fa login e non ha sessioni,
 * si limita a verificare il JWT che il gateway mette in ogni richiesta.
 *  - JWT assente o non valido (firma, scadenza, issuer) -> 401 Unauthorized
 *  - JWT valido ma ruolo insufficiente (@PreAuthorize)  -> 403 Forbidden
 * Così il servizio si protegge da solo, anche se qualcuno lo raggiungesse
 * senza passare dal gateway.
 */
@Configuration
@EnableMethodSecurity // attiva @PreAuthorize sui metodi dei controller
public class SecurityConfig {

    @Bean
    SecurityFilterChain catenaSicurezza(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(regole -> regole
                        // Health pubblico: lo usano gli healthcheck di Docker (senza token)
                        .requestMatchers("/health", "/health/**").permitAll()
                        .anyRequest().authenticated())
                // Verifica "Authorization: Bearer <jwt>" con le regole di application.yml
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                // Nessuna sessione: ogni richiesta porta il suo JWT (servizio stateless)
                .sessionManagement(sessione -> sessione.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF serve contro i cookie inviati in automatico dal browser; qui
                // l'autenticazione è un header messo esplicitamente dal gateway.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
