package it.ginvault.gateway.jwt;

import java.util.Optional;

import it.ginvault.gateway.sicurezza.UtenteGinVault;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Il cuore del pattern BFF: per ogni richiesta inoltrata a un servizio,
 *  1) TOGLIE qualsiasi header Authorization arrivato dal client (nessuno può
 *     presentarsi ai servizi con un token fabbricato da sé);
 *  2) se l'utente ha una sessione autenticata, firma un JWT nuovo con la sua
 *     identità e i suoi ruoli e lo aggiunge come "Authorization: Bearer ...".
 *
 * È un GlobalFilter: vale per tutte le route, comprese le richieste di upgrade
 * WebSocket verso notifiche (l'handshake è una normale GET HTTP, quindi riceve
 * lo stesso header).
 *
 * Perché non il filtro TokenRelay di Spring Cloud Gateway: inoltrerebbe il token
 * di Google/GitHub, che non contiene i nostri ruoli (e quello di GitHub non è
 * nemmeno un JWT).
 */
@Component
class InoltroJwtFilter implements GlobalFilter, Ordered {

    private final EmettitoreJwt emettitore;

    InoltroJwtFilter(EmettitoreJwt emettitore) {
        this.emettitore = emettitore;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(OAuth2AuthenticationToken.class::isInstance)
                .map(principal -> emettitore.emetti(UtenteGinVault.da((OAuth2AuthenticationToken) principal)))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())   // utente anonimo (es. /api/*/health)
                .flatMap(jwt -> chain.filter(conAutorizzazione(exchange, jwt)));
    }

    private static ServerWebExchange conAutorizzazione(ServerWebExchange exchange, Optional<String> jwt) {
        ServerHttpRequest richiesta = exchange.getRequest().mutate()
                .headers(header -> {
                    header.remove(HttpHeaders.AUTHORIZATION);
                    jwt.ifPresent(header::setBearerAuth);
                })
                .build();
        return exchange.mutate().request(richiesta).build();
    }

    /** Qualsiasi valore prima dei filtri di routing (che hanno priorità minima) va bene. */
    @Override
    public int getOrder() {
        return 0;
    }
}
