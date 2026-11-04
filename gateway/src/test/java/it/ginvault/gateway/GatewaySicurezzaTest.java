package it.ginvault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OAuth2LoginMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifica il flusso BFF del gateway senza Google/GitHub veri:
 * mockOAuth2Login() simula un utente che ha GIÀ fatto il login (sessione valida),
 * e un piccolo server HTTP locale fa la parte del microservizio "edizioni",
 * registrando gli header che riceve.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        "spring.security.oauth2.client.registration.github.client-id=test-github",
        "spring.security.oauth2.client.registration.github.client-secret=test"
})
class GatewaySicurezzaTest {

    private static HttpServer servizioFinto;
    private static final AtomicReference<Headers> headerRicevuti = new AtomicReference<>();
    private static final AtomicReference<String> pathRicevuto = new AtomicReference<>();

    @DynamicPropertySource
    static void puntaAlServizioFinto(DynamicPropertyRegistry registro) throws IOException {
        servizioFinto = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servizioFinto.createContext("/", scambio -> {
            headerRicevuti.set(scambio.getRequestHeaders());
            pathRicevuto.set(scambio.getRequestURI().getPath());
            byte[] corpo = "ok".getBytes(StandardCharsets.UTF_8);
            scambio.sendResponseHeaders(200, corpo.length);
            scambio.getResponseBody().write(corpo);
            scambio.close();
        });
        servizioFinto.start();
        registro.add("EDIZIONI_URI", () -> "http://127.0.0.1:" + servizioFinto.getAddress().getPort());
    }

    @AfterAll
    static void fermaServizioFinto() {
        servizioFinto.stop(0);
    }

    @Autowired
    private ApplicationContext contesto;

    @Autowired
    private RSAKey chiaveDiFirma;

    private WebTestClient client;

    @BeforeEach
    void prepara() {
        client = WebTestClient.bindToApplicationContext(contesto).apply(springSecurity()).configureClient().build();
        headerRicevuti.set(null);
        pathRicevuto.set(null);
    }

    // ---------------------------------------------------------------- inoltro del JWT

    @Test
    void utenteLoggatoArrivaAlServizioConUnJwtFirmatoDalGateway() throws Exception {
        client.mutateWith(utenteGitHub("SOCIO"))
                .get().uri("/api/edizioni/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-fabbricato-dal-client")
                .header(HttpHeaders.COOKIE, "SESSION=abc")
                .exchange()
                .expectStatus().isOk();

        Headers ricevuti = headerRicevuti.get();
        assertThat(pathRicevuto.get()).isEqualTo("/me");               // StripPrefix=2
        assertThat(ricevuti.get(HttpHeaders.COOKIE)).isNull();          // RemoveRequestHeader=Cookie
        String autorizzazione = ricevuti.getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(autorizzazione).startsWith("Bearer ").doesNotContain("fabbricato");

        // Verifica fatta come la fanno i servizi: firma con la chiave pubblica + issuer + scadenza
        Jwt jwt = decoderComeIServizi().decode(autorizzazione.substring("Bearer ".length()));
        assertThat(jwt.getSubject()).isEqualTo("github:42");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Mario Rossi");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SOCIO");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(jwt.getHeaders()).containsEntry("kid", chiaveDiFirma.getKeyID());
    }

    @Test
    void richiestaAnonimaNonPortaAlServizioUnAuthorizationFalso() {
        client.get().uri("/api/edizioni/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-fabbricato-dal-client")
                .exchange()
                .expectStatus().isOk();

        assertThat(pathRicevuto.get()).isEqualTo("/health");
        assertThat(headerRicevuti.get().get(HttpHeaders.AUTHORIZATION)).isNull();
    }

    // ---------------------------------------------------------------- richieste non autenticate

    @Test
    void apiSenzaLoginRisponde401ENonArrivaAlServizio() {
        client.get().uri("/api/edizioni/me").exchange().expectStatus().isUnauthorized();
        assertThat(headerRicevuti.get()).isNull();
    }

    @Test
    void authMeSenzaLoginRisponde401() {
        client.get().uri("/auth/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void browserSenzaLoginVieneMandatoAllaPaginaDiLogin() {
        client.get().uri("/").accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location("/login");
    }

    @Test
    void laPaginaDiLoginOffreGoogleEGitHub() {
        client.get().uri("/login").accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(html -> assertThat(html)
                        .contains("/oauth2/authorization/google")
                        .contains("/oauth2/authorization/github"));
    }

    @Test
    void ilLoginGoogleRimandaIlBrowserAGoogleConClientIdEState() {
        client.get().uri("/oauth2/authorization/google")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location)
                        .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                        .contains("response_type=code")
                        .contains("client_id=test-google")
                        .contains("state=")
                        // nel test la richiesta non ha host: in Docker diventa
                        // http://localhost:8080/login/oauth2/code/google
                        .contains("redirect_uri=")
                        .contains("/login/oauth2/code/google"));
    }

    // ---------------------------------------------------------------- endpoint del gateway

    @Test
    void laJwksPubblicaSoloLaChiavePubblica() {
        client.get().uri("/.well-known/jwks.json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys[0].kid").isEqualTo(chiaveDiFirma.getKeyID())
                .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                .jsonPath("$.keys[0].alg").isEqualTo("RS256")
                .jsonPath("$.keys[0].d").doesNotExist();   // "d" = esponente privato
    }

    @Test
    void authMeRestituisceIdentitaERuoliDallaSessione() {
        client.mutateWith(utenteGitHub("SOCIO", "ADMIN"))
                .get().uri("/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sub").isEqualTo("github:42")
                .jsonPath("$.nome").isEqualTo("Mario Rossi")
                .jsonPath("$.ruoli[0]").isEqualTo("ADMIN")
                .jsonPath("$.ruoli[1]").isEqualTo("SOCIO");
    }

    @Test
    void leRouteDelGatewaySonoVisibiliSoloAgliAdmin() {
        client.mutateWith(utenteGitHub("CLIENTE")).get().uri("/gateway/routes")
                .exchange().expectStatus().isForbidden();
        client.mutateWith(utenteGitHub("ADMIN")).get().uri("/gateway/routes")
                .exchange().expectStatus().isOk();
    }

    @Test
    void healthMostraIDettagliSoloAgliAdmin() {
        client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
        client.mutateWith(utenteGitHub("ADMIN")).get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components").exists();
    }

    // ---------------------------------------------------------------- supporto

    /** Utente GitHub con id 42 e login mrossi, già autenticato con i ruoli indicati. */
    private static OAuth2LoginMutator utenteGitHub(String... ruoli) {
        List<GrantedAuthority> autorita = Arrays.stream(ruoli)
                .<GrantedAuthority>map(ruolo -> new SimpleGrantedAuthority("ROLE_" + ruolo))
                .toList();
        Map<String, Object> attributi = Map.of("id", 42, "login", "mrossi", "name", "Mario Rossi");
        return mockOAuth2Login()
                .clientRegistration(CommonOAuth2Provider.GITHUB.getBuilder("github")
                        .clientId("test-github").clientSecret("test").build())
                .oauth2User(new DefaultOAuth2User(autorita, attributi, "id"));
    }

    private NimbusJwtDecoder decoderComeIServizi() throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(chiaveDiFirma.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("ginvault-gateway"));
        return decoder;
    }
}
