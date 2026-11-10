package it.ginvault.produzione.infrastruttura.magazzino;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Token relay, come Edizioni verso Ricette: ogni chiamata gRPC al magazzino
 * porta lo stesso JWT arrivato a Produzione dal gateway, nel metadata
 * "authorization: Bearer <jwt>" (l'equivalente gRPC dell'header HTTP).
 *
 * Così il magazzino controlla da solo chi chiama e con che ruolo
 * (AutenticazioneGrpc), e registra nella riserva che l'ha chiesta Marco.
 *
 * Il JWT si legge dal SecurityContext del thread della richiesta HTTP:
 * interceptCall gira lì, perché lo stub bloccante parte dal thread chiamante.
 */
public class InoltroJwt implements ClientInterceptor {

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> metodo, CallOptions opzioni, Channel canale) {
        String jwt = jwtDellUtente();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(canale.newCall(metodo, opzioni)) {
            @Override
            public void start(Listener<R> ascoltatore, Metadata intestazioni) {
                if (jwt != null) {
                    intestazioni.put(AUTHORIZATION, "Bearer " + jwt);
                }
                super.start(ascoltatore, intestazioni);
            }
        };
    }

    /** null fuori da una richiesta autenticata: il magazzino risponderà UNAUTHENTICATED. */
    private static String jwtDellUtente() {
        Authentication utente = SecurityContextHolder.getContext().getAuthentication();
        return utente instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null;
    }
}
