package it.ginvault.gateway.sicurezza;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.WebFilter;

import reactor.core.publisher.Mono;

/**
 * Sicurezza del gateway. Il gateway è reattivo (WebFlux), quindi si usano le
 * classi "Server..." di Spring Security al posto di quelle servlet.
 *
 * Due catene di filtri, scelte in base al path (la prima che corrisponde vince):
 *  1) API  (/api/**, /auth/**): chi non è loggato riceve 401. Il front-end
 *     (JavaScript) deciderà di mandare l'utente al login.
 *  2) Web  (tutto il resto): login OAuth2, pagina di login, logout. Chi non è
 *     loggato viene rediretto a /login.
 * Le due catene condividono la stessa SESSIONE: il login fatto nella catena web
 * vale anche per le API.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityWebFilterChain catenaApi(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/**", "/auth/**"))
                .authorizeExchange(regole -> regole
                        // Health dei servizi pubblico (risponde solo UP/DOWN, vedi i loro application.yml)
                        .pathMatchers("/api/*/health").permitAll()
                        // Tutto il resto richiede il login. L'autorizzazione fine
                        // (ruoli sui singoli endpoint) la fanno i servizi con @PreAuthorize.
                        .anyExchange().authenticated())
                // L'utente autenticato viene letto dalla sessione creata dal login
                .securityContextRepository(new WebSessionServerSecurityContextRepository())
                // Non autenticato -> 401 (nessun redirect verso una pagina HTML)
                .exceptionHandling(errori -> errori
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                // CSRF ATTIVO, con il token in un cookie (schema "double submit cookie").
                // Con una sessione a cookie, un sito malevolo potrebbe far partire una
                // POST dal browser dell'utente. Quindi:
                //  - le risposte delle API portano il cookie XSRF-TOKEN, leggibile da
                //    JavaScript (HttpOnly=false);
                //  - ogni richiesta che modifica (POST, PUT, DELETE...) deve rimandare
                //    lo stesso valore nell'header X-XSRF-TOKEN, altrimenti 403.
                // Il sito malevolo può far inviare i cookie, ma non può LEGGERLI per
                // copiare il valore nell'header. È lo schema che usano i front-end
                // JavaScript (Angular lo gestisce da solo), e serve anche alle demo con
                // curl o con la console del browser.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        // Header con il token "in chiaro", uguale al cookie. Il gestore di
                        // default (Xor) vuole un valore mascherato, diverso a ogni risposta:
                        // è pensato per i form HTML generati dal server, non per le SPA.
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                // Il token è "pigro": senza questo filtro il cookie verrebbe scritto solo
                // quando qualcuno lo legge. Così arriva già con la prima GET (es. /auth/me).
                .addFilterAfter(scriviIlCookieCsrf(), SecurityWebFiltersOrder.CSRF)
                .build();
    }

    /** Legge il token CSRF a ogni richiesta delle API: se il cookie XSRF-TOKEN manca, viene creato. */
    private static WebFilter scriviIlCookieCsrf() {
        return (scambio, catena) -> {
            Mono<CsrfToken> token = scambio.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
            return token.then(catena.filter(scambio));
        };
    }

    @Bean
    @Order(2)
    SecurityWebFilterChain catenaWeb(ServerHttpSecurity http) {
        return http
                .authorizeExchange(regole -> regole
                        // Pubblici: health per Docker, probe di Kubernetes (/health/liveness,
                        // /health/readiness), chiavi pubbliche per i servizi
                        .pathMatchers("/health", "/health/**", "/.well-known/jwks.json").permitAll()
                        // Elenco delle route del gateway: solo amministratori
                        .pathMatchers("/gateway/**").hasRole("ADMIN")
                        .anyExchange().authenticated())

                // --- Login OAuth2 con Google/GitHub (flusso Authorization Code) ---
                // Endpoint creati da Spring Security:
                //   GET /login                            pagina con i link ai provider
                //   GET /oauth2/authorization/{provider}  avvia il login (redirect al provider)
                //   GET /login/oauth2/code/{provider}     ritorno dal provider con il "code"
                .oauth2Login(login -> login
                        // I token del provider stanno nella SESSIONE e non in una mappa in
                        // memoria: quando la sessione passerà su Redis, più repliche del
                        // gateway vedranno gli stessi dati.
                        .authorizedClientRepository(new WebSessionServerOAuth2AuthorizedClientRepository())
                        // Dopo il login: per ora /auth/me, poi diventerà la home del front-end
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler("/auth/me")))

                // --- Logout: POST /logout invalida la sessione (GET /logout mostra la conferma) ---
                .logout(logout -> logout.logoutSuccessHandler(dopoLogout()))
                .build();
    }

    private static RedirectServerLogoutSuccessHandler dopoLogout() {
        RedirectServerLogoutSuccessHandler dopoLogout = new RedirectServerLogoutSuccessHandler();
        dopoLogout.setLogoutSuccessUrl(URI.create("/login?logout"));
        return dopoLogout;
    }
}
