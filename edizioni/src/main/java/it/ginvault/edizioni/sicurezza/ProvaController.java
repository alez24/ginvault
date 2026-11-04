package it.ginvault.edizioni.sicurezza;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint DI PROVA della sicurezza (verranno sostituiti dalla logica di dominio).
 * Dal gateway: /api/edizioni/me e /api/edizioni/prova
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
        return new Identita("edizioni", autenticazione.getToken().getSubject(),
                autenticazione.getToken().getClaimAsString("name"), ruoli);
    }

    /**
     * Area soci: solo SOCIO.
     * Attenzione: SOCIO dà accesso all'area soci, ma il diritto di PRELAZIONE su
     * un'edizione è una regola di dominio, che Edizioni verificherà con il mock
     * del Club, non con Spring Security.
     */
    @GetMapping("/prova")
    @PreAuthorize("hasRole('SOCIO')")
    public Map<String, String> prova() {
        return Map.of("servizio", "edizioni", "esito", "accesso consentito al ruolo SOCIO (area soci)");
    }

    public record Identita(String servizio, String sub, String nome, List<String> ruoli) {
    }
}
