package it.ginvault.gateway.jwt;

import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Chiave di firma del JWT interno.
 *
 * Firma ASIMMETRICA (RSA, algoritmo RS256):
 *  - la chiave PRIVATA resta solo nel gateway: solo lui può firmare token;
 *  - la chiave PUBBLICA è pubblicata su /.well-known/jwks.json: i servizi la
 *    usano per verificare la firma, ma non possono creare token.
 *
 * Per ora la coppia di chiavi viene generata in memoria all'avvio: dopo un
 * riavvio del gateway cambia la chiave (e il "kid"), quindi serve un nuovo login
 * (anche la sessione è in memoria). Quando il gateway avrà più repliche con
 * sessione su Redis, tutte dovranno firmare con la STESSA chiave: andrà letta da
 * un segreto condiviso (Docker/Kubernetes Secret) invece che generata qui.
 */
@Configuration
class JwtConfig {

    @Bean
    RSAKey chiaveDiFirma() {
        try {
            return new RSAKeyGenerator(2048)
                    // "kid" (key id): finisce nell'header del JWT e dice ai servizi
                    // quale chiave della JWKS usare per la verifica
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Impossibile generare la chiave RSA del gateway", e);
        }
    }

    /** Encoder di Spring Security che firma i JWT con la chiave privata. */
    @Bean
    JwtEncoder jwtEncoder(RSAKey chiaveDiFirma) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(chiaveDiFirma)));
    }
}
