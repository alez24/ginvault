package it.ginvault.gateway.sicurezza;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /auth/me: chi sono e che ruoli ho (letto dalla sessione del gateway).
 * Servirà al front-end per sapere se l'utente è loggato e cosa mostrargli.
 * Il JWT invece non viene mai restituito al browser (pattern BFF).
 */
@RestController
class AuthController {

    @GetMapping("/auth/me")
    UtenteGinVault me(OAuth2AuthenticationToken autenticazione) {
        return UtenteGinVault.da(autenticazione);
    }
}
