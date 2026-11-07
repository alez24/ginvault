package it.ginvault.edizioni.sicurezza;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Fa la parte del gateway nei test: ha una chiave RSA, pubblica la JWKS su un
 * piccolo server HTTP locale e firma token come quelli del gateway vero.
 * Il servizio sotto test verifica i token esattamente come in produzione.
 */
public final class GatewayDiProva {

    public static final String ISSUER = "ginvault-gateway";
    private static final String KID = "chiave-di-prova";

    private final RSAKey chiave;
    private final HttpServer server;

    public GatewayDiProva() {
        try {
            chiave = nuovaChiave();
            byte[] jwks = new JWKSet(chiave.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", scambio -> {
                scambio.getResponseHeaders().add("Content-Type", "application/json");
                scambio.sendResponseHeaders(200, jwks.length);
                scambio.getResponseBody().write(jwks);
                scambio.close();
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    public void ferma() {
        server.stop(0);
    }

    /** Token valido (5 minuti) dell'utente github:42 con i ruoli indicati. */
    public String token(String... ruoli) {
        return firma(chiave, ISSUER, Instant.now().plus(Duration.ofMinutes(5)), ruoli);
    }

    public String tokenScaduto(String... ruoli) {
        return firma(chiave, ISSUER, Instant.now().minus(Duration.ofHours(1)), ruoli);
    }

    public String tokenDiUnAltroEmittente(String... ruoli) {
        return firma(chiave, "un-altro-emittente", Instant.now().plus(Duration.ofMinutes(5)), ruoli);
    }

    /** Stesso "kid" ma chiave diversa: la firma non torna con la chiave pubblica della JWKS. */
    public String tokenConFirmaFalsa(String... ruoli) {
        try {
            return firma(nuovaChiave(), ISSUER, Instant.now().plus(Duration.ofMinutes(5)), ruoli);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey nuovaChiave() throws Exception {
        return new RSAKeyGenerator(2048).keyID(KID).generate();
    }

    private static String firma(RSAKey chiave, String issuer, Instant scadenza, String... ruoli) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("github:42")
                    .claim("name", "Mario Rossi")
                    .claim("roles", List.of(ruoli))
                    .issueTime(Date.from(scadenza.minus(Duration.ofMinutes(5))))
                    .expirationTime(Date.from(scadenza))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chiave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(chiave));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
