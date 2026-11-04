package it.ginvault.magazzino.sicurezza;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint DI PROVA della sicurezza (verranno sostituiti dalla logica di dominio).
 * Dal gateway: /api/magazzino/me e /api/magazzino/prova
 */
@RestController
public class ProvaController {

    /** Qualsiasi utente autenticato: mostra cosa il servizio legge dal JWT. */
    @GetMapping("/me")
    public Identita me(JwtAuthenticationToken autenticazione) {
        List<String> ruoli = autenticazione.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new Identita("magazzino", autenticazione.getToken().getSubject(),
                autenticazione.getToken().getClaimAsString("name"), ruoli);
    }

    /** Solo RESP_ACQUISTI (registrare consegne e gestire i lotti di botaniche). */
    @GetMapping("/prova")
    @PreAuthorize("hasRole('RESP_ACQUISTI')")
    public Map<String, String> prova() {
        return Map.of("servizio", "magazzino", "esito", "accesso consentito al ruolo RESP_ACQUISTI");
    }

    public record Identita(String servizio, String sub, String nome, List<String> ruoli) {
    }
}
