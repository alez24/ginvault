package it.ginvault.pagamenti.sicurezza;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint DI PROVA della sicurezza (verranno sostituiti dalla logica di dominio).
 * Dal gateway: /api/pagamenti/me e /api/pagamenti/prova
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
        return new Identita("pagamenti", autenticazione.getToken().getSubject(),
                autenticazione.getToken().getClaimAsString("name"), ruoli);
    }

    /** Solo CLIENTE (area clienti). */
    @GetMapping("/prova")
    @PreAuthorize("hasRole('CLIENTE')")
    public Map<String, String> prova() {
        return Map.of("servizio", "pagamenti", "esito", "accesso consentito al ruolo CLIENTE");
    }

    public record Identita(String servizio, String sub, String nome, List<String> ruoli) {
    }
}
