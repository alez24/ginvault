package it.ginvault.gateway.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOAuth2Login;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.OAuth2LoginMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Login e logout visti dal front-end, con un GitHub FINTO: un piccolo server
 * HTTP locale risponde al posto di github.com per lo scambio del "code" con il
 * token e per il profilo (/user). Così il flusso Authorization Code gira davvero
 * nel gateway, dall'avvio fino al redirect finale.
 *
 * Il front-end qui è il dev server di Vite (http://localhost:5173), come in sviluppo.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google",
        "spring.security.oauth2.client.registration.google.client-secret=test",
        "spring.security.oauth2.client.registration.github.client-id=test-github",
        "spring.security.oauth2.client.registration.github.client-secret=test",
        "ginvault.frontend.url=http://localhost:5173",
        "ginvault.ruoli.distillatore=github:mrossi"
})
class GatewayLoginTest {

    private static final String HOME = "http://localhost:5173/";

    private static HttpServer githubFinto;

    @DynamicPropertySource
    static void puntaAGitHubFinto(DynamicPropertyRegistry registro) throws IOException {
        githubFinto = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        githubFinto.createContext("/login/oauth/access_token",
                json("{\"access_token\":\"token-di-prova\",\"token_type\":\"bearer\",\"scope\":\"read:user\"}"));
        githubFinto.createContext("/user", json("{\"id\":42,\"login\":\"mrossi\",\"name\":\"Mario Rossi\"}"));
        githubFinto.start();
        String base = "http://127.0.0.1:" + githubFinto.getAddress().getPort();
        // Gli altri dati del provider (authorize, attributo "id") restano quelli di GitHub
        registro.add("spring.security.oauth2.client.provider.github.token-uri", () -> base + "/login/oauth/access_token");
        registro.add("spring.security.oauth2.client.provider.github.user-info-uri", () -> base + "/user");
    }

    @AfterAll
    static void fermaGitHubFinto() {
        githubFinto.stop(0);
    }

    @Autowired
    private ApplicationContext contesto;

    private WebTestClient client;

    @BeforeEach
    void prepara() {
        client = WebTestClient.bindToApplicationContext(contesto).apply(springSecurity()).configureClient().build();
    }

    // ---------------------------------------------------------------- login

    @Test
    void ilRedirectUriSegueLHostDellaRichiesta() {
        // Il proxy di Vite inoltra /oauth2 con Host: localhost:8080 (changeOrigin):
        // GitHub e Google rimandano quindi all'indirizzo registrato, non alla 5173.
        Avvio avvio = avviaIlLoginGitHub();

        assertThat(avvio.parametri().getFirst("redirect_uri"))
                .isEqualTo("http://localhost:8080/login/oauth2/code/github");
    }

    @Test
    void dopoIlLoginIlBrowserTornaAllaHomeDelFrontend() {
        Avvio avvio = avviaIlLoginGitHub();

        FluxExchangeResult<Void> ritorno = client.get()
                .uri(callback(Map.of("code", "codice-di-prova", "state", avvio.state())))
                .cookie("SESSION", avvio.sessione())
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location(HOME)
                .returnResult(Void.class);

        // Nuova sessione dopo il login (protezione dalla "session fixation"): il
        // front-end ora può chiedere /auth/me con questo cookie
        ResponseCookie sessione = ritorno.getResponseCookies().getFirst("SESSION");
        assertThat(sessione).isNotNull();
        assertThat(sessione.getValue()).isNotEqualTo(avvio.sessione());

        client.get().uri("/auth/me")
                .cookie("SESSION", sessione.getValue())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sub").isEqualTo("github:42")
                .jsonPath("$.nome").isEqualTo("Mario Rossi")
                .jsonPath("$.ruoli[0]").isEqualTo("DISTILLATORE");
    }

    @Test
    void seLUtenteAnnullaSulProviderTornaAllaHomeConUnAvviso() {
        Avvio avvio = avviaIlLoginGitHub();

        client.get()
                .uri(callback(Map.of("error", "access_denied", "state", avvio.state())))
                .cookie("SESSION", avvio.sessione())
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location(HOME + "?accesso=negato");
    }

    // ---------------------------------------------------------------- logout

    @Test
    void ilLogoutDelFrontendUsaLoStessoTokenCsrfDelleApi() {
        client.mutateWith(utenteGitHub())
                .post().uri("/logout")
                .cookie("XSRF-TOKEN", "token-del-cookie")
                .header("X-XSRF-TOKEN", "token-del-cookie")
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location(HOME);
    }

    @Test
    void unLogoutSenzaTokenCsrfRisponde403() {
        // Altrimenti un altro sito potrebbe far uscire l'utente con un form nascosto
        client.mutateWith(utenteGitHub())
                .post().uri("/logout")
                .exchange()
                .expectStatus().isForbidden();
    }

    // ---------------------------------------------------------------- configurazione

    @Test
    void laHomeDelFrontendEStessaOrigineOppureUnIndirizzoHttp() {
        assertThat(SecurityConfig.homeDelFrontend("/")).hasToString("/");
        assertThat(SecurityConfig.homeDelFrontend("")).hasToString("/");
        assertThat(SecurityConfig.homeDelFrontend("http://localhost:5173")).hasToString(HOME);
        assertThat(SecurityConfig.homeDelFrontend("https://ginvault.example/")).hasToString("https://ginvault.example/");

        for (String sbagliato : List.of("//altro-sito.example", "javascript:alert(1)", "ftp://localhost")) {
            assertThatIllegalStateException().isThrownBy(() -> SecurityConfig.homeDelFrontend(sbagliato));
        }
    }

    // ---------------------------------------------------------------- supporto

    /** Primo passo del login: il gateway salva la richiesta in sessione e manda il browser a GitHub. */
    private Avvio avviaIlLoginGitHub() {
        FluxExchangeResult<Void> risposta = client.get()
                .uri("http://localhost:8080/oauth2/authorization/github")
                .exchange()
                .expectStatus().isFound()
                .returnResult(Void.class);
        URI github = risposta.getResponseHeaders().getLocation();
        assertThat(github).isNotNull();
        assertThat(github.toString()).startsWith("https://github.com/login/oauth/authorize");

        MultiValueMap<String, String> parametri = UriComponentsBuilder.fromUri(github).build().getQueryParams();
        MultiValueMap<String, String> decodificati = new LinkedMultiValueMap<>();
        parametri.forEach((nome, valori) -> valori.forEach(
                valore -> decodificati.add(nome, URLDecoder.decode(valore, StandardCharsets.UTF_8))));
        ResponseCookie sessione = risposta.getResponseCookies().getFirst("SESSION");
        assertThat(sessione).isNotNull();
        return new Avvio(decodificati, sessione.getValue());
    }

    private record Avvio(MultiValueMap<String, String> parametri, String sessione) {
        String state() {
            return parametri.getFirst("state");
        }
    }

    /** Il ritorno da GitHub, come lo farebbe il browser. */
    private static URI callback(Map<String, String> parametri) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("http://localhost:8080/login/oauth2/code/github");
        parametri.forEach(uri::queryParam);
        return uri.encode().build().toUri();
    }

    private static HttpHandler json(String corpo) {
        return scambio -> {
            byte[] byteDelCorpo = corpo.getBytes(StandardCharsets.UTF_8);
            scambio.getResponseHeaders().add("Content-Type", "application/json");
            scambio.sendResponseHeaders(200, byteDelCorpo.length);
            scambio.getResponseBody().write(byteDelCorpo);
            scambio.close();
        };
    }

    private static OAuth2LoginMutator utenteGitHub() {
        return mockOAuth2Login()
                .clientRegistration(CommonOAuth2Provider.GITHUB.getBuilder("github")
                        .clientId("test-github").clientSecret("test").build())
                .oauth2User(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_DISTILLATORE")),
                        Map.of("id", 42, "login", "mrossi", "name", "Mario Rossi"), "id"));
    }
}
