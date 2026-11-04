package it.ginvault.gateway.sicurezza;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * L'utente come lo vede GinVault, ricavato dall'autenticazione in sessione.
 * È il contenuto del JWT interno e la risposta di GET /auth/me.
 *
 * @param sub   identificativo stabile: "provider:id" (es. github:1234567, google:1098...)
 *              -> è il riferimento all'utente che useranno i servizi (es. nelle prenotazioni)
 * @param nome  nome visualizzato (GitHub: login se il nome non è impostato)
 * @param email può mancare (GitHub con email privata)
 * @param ruoli ruoli GinVault senza prefisso: SOCIO, CLIENTE, ...
 */
public record UtenteGinVault(String sub, String nome, String email, List<String> ruoli) {

    private static final String PREFISSO_RUOLO = "ROLE_";

    public static UtenteGinVault da(OAuth2AuthenticationToken autenticazione) {
        OAuth2User utente = autenticazione.getPrincipal();
        String sub = autenticazione.getAuthorizedClientRegistrationId() + ":" + autenticazione.getName();
        String nome = testo(utente.getAttribute("name"));
        if (nome == null) {
            nome = testo(utente.getAttribute("login"));
        }
        String email = testo(utente.getAttribute("email"));
        List<String> ruoli = autenticazione.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(PREFISSO_RUOLO))
                .map(authority -> authority.substring(PREFISSO_RUOLO.length()))
                .sorted()
                .toList();
        return new UtenteGinVault(sub, nome, email, ruoli);
    }

    /** Attributo come testo; null se assente o vuoto (es. email privata su GitHub). */
    private static String testo(Object valore) {
        return valore == null || valore.toString().isBlank() ? null : valore.toString();
    }
}
