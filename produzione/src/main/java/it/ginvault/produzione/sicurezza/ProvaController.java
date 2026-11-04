package it.ginvault.produzione.sicurezza;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint DI PROVA della sicurezza (verranno sostituiti dalla logica di dominio).
 * Dal gateway: /api/produzione/me e /api/produzione/prova
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
        return new Identita("produzione", autenticazione.getToken().getSubject(),
                autenticazione.getToken().getClaimAsString("name"), ruoli);
    }

    /** Solo DISTILLATORE (registrare rese e imbottigliamenti). */
    @GetMapping("/prova")
    @PreAuthorize("hasRole('DISTILLATORE')")
    public Map<String, String> prova() {
        return Map.of("servizio", "produzione", "esito", "accesso consentito al ruolo DISTILLATORE");
    }

    public record Identita(String servizio, String sub, String nome, List<String> ruoli) {
    }
}
