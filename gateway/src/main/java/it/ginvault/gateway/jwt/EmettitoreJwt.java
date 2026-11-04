package it.ginvault.gateway.jwt;

import java.time.Instant;

import it.ginvault.gateway.sicurezza.UtenteGinVault;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Crea il JWT interno di GinVault per un utente.
 *
 * Esempio di contenuto (payload):
 * <pre>
 * { "iss": "ginvault-gateway", "sub": "github:1234567", "name": "Mario Rossi",
 *   "roles": ["DISTILLATORE"], "iat": 1730000000, "exp": 1730000300 }
 * </pre>
 * Header: { "alg": "RS256", "kid": "&lt;id della chiave&gt;" }
 */
@Component
public class EmettitoreJwt {

    private final JwtEncoder encoder;
    private final JwtProperties proprieta;

    EmettitoreJwt(JwtEncoder encoder, JwtProperties proprieta) {
        this.encoder = encoder;
        this.proprieta = proprieta;
    }

    public String emetti(UtenteGinVault utente) {
        Instant ora = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(proprieta.issuer())
                .subject(utente.sub())
                .issuedAt(ora)
                .expiresAt(ora.plus(proprieta.durata()))
                // I servizi trasformano "roles" in authority ROLE_* (vedi il loro application.yml)
                .claim("roles", utente.ruoli());
        if (utente.nome() != null) {
            claims.claim("name", utente.nome());
        }
        if (utente.email() != null) {
            claims.claim("email", utente.email());
        }
        // L'encoder aggiunge da solo il "kid" della chiave usata
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
