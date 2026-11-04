package it.ginvault.gateway.jwt;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /.well-known/jwks.json: le chiavi PUBBLICHE del gateway in formato JWKS
 * (JSON Web Key Set, RFC 7517). I servizi la scaricano una volta, la tengono in
 * cache e la riscaricano solo se arriva un token con un "kid" sconosciuto.
 */
@RestController
class JwksController {

    private final Map<String, Object> jwks;

    JwksController(RSAKey chiaveDiFirma) {
        // toPublicJWK(): solo modulo ed esponente pubblico, MAI la parte privata
        this.jwks = new JWKSet(chiaveDiFirma.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return jwks;
    }
}
