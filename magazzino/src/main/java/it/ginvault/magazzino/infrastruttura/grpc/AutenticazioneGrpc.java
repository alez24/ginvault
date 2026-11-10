package it.ginvault.magazzino.infrastruttura.grpc;

import java.util.Map;
import java.util.Set;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * La sicurezza da "resource server" anche per gRPC: le stesse regole delle
 * chiamate REST (SecurityConfig), applicate a ogni chiamata prima che arrivi
 * a ServizioGiacenze.
 *
 *  - metadata "authorization: Bearer <jwt>" mancante o JWT non valido
 *    (firma, scadenza, issuer)                      -> UNAUTHENTICATED (il 401 di gRPC)
 *  - JWT valido ma ruolo non ammesso per il metodo  -> PERMISSION_DENIED (il 403)
 *  - chiavi del gateway (JWKS) non raggiungibili    -> UNAVAILABLE: il token
 *    forse è buono, è il magazzino che non può controllarlo
 *  - metodo senza regola                            -> PERMISSION_DENIED: si nega
 *    tutto quello che non è previsto
 *
 * JwtDecoder e JwtAuthenticationConverter sono quelli che Spring Boot crea da
 * application.yml per le chiamate REST: stessa JWKS, stesso issuer, stesso
 * claim "roles" con il prefisso ROLE_.
 *
 * Il JWT è quello dell'utente (Marco), inoltrato da Produzione: Magazzino sa
 * chi ha chiesto la riserva, non solo quale servizio.
 */
public class AutenticazioneGrpc implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AutenticazioneGrpc.class);

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER = "Bearer ";

    /** L'utente della chiamata in corso: lo legge ServizioGiacenze con utente(). */
    private static final Context.Key<Authentication> UTENTE = Context.key("ginvault-utente");

    private final JwtDecoder decodificatore;
    private final JwtAuthenticationConverter convertitore;
    /** Nome completo del metodo (es. "ginvault.giacenze.v1.Giacenze/RiservaLotto") -> authority ammesse. */
    private final Map<String, Set<String>> ruoliPerMetodo;

    public AutenticazioneGrpc(JwtDecoder decodificatore, JwtAuthenticationConverter convertitore,
                              Map<String, Set<String>> ruoliPerMetodo) {
        this.decodificatore = decodificatore;
        this.convertitore = convertitore;
        this.ruoliPerMetodo = Map.copyOf(ruoliPerMetodo);
    }

    /** L'utente autenticato della chiamata gRPC in corso. */
    static Authentication utente() {
        Authentication utente = UTENTE.get();
        if (utente == null) {
            throw new IllegalStateException("Chiamata gRPC senza AutenticazioneGrpc");
        }
        return utente;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> chiamata, Metadata intestazioni,
                                                       ServerCallHandler<Q, R> prossimo) {
        String metodo = chiamata.getMethodDescriptor().getFullMethodName();
        Set<String> ammessi = ruoliPerMetodo.get(metodo);
        if (ammessi == null) {
            return chiudi(chiamata, Status.PERMISSION_DENIED.withDescription("Metodo non previsto: " + metodo));
        }

        String intestazione = intestazioni.get(AUTHORIZATION);
        if (intestazione == null || !intestazione.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return chiudi(chiamata, Status.UNAUTHENTICATED
                    .withDescription("Serve il JWT dell'utente nel metadata authorization (Bearer <jwt>)"));
        }

        Authentication utente;
        try {
            utente = convertitore.convert(decodificatore.decode(intestazione.substring(BEARER.length()).trim()));
        } catch (BadJwtException errore) {
            // Firma, scadenza, issuer o formato: è il token a non andare
            return chiudi(chiamata, Status.UNAUTHENTICATED.withDescription("JWT non valido: " + errore.getMessage()));
        } catch (JwtException errore) {
            // Tipicamente la JWKS del gateway non risponde: non è colpa del token
            log.warn("Impossibile verificare un JWT su {}: {}", metodo, errore.getMessage());
            return chiudi(chiamata, Status.UNAVAILABLE
                    .withDescription("Il magazzino non riesce a verificare il JWT in questo momento"));
        }

        boolean autorizzato = utente.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ammessi::contains);
        if (!autorizzato) {
            return chiudi(chiamata, Status.PERMISSION_DENIED
                    .withDescription("Il ruolo non basta per " + metodo + ": serve uno tra " + ammessi));
        }

        // L'utente viaggia nel Context di gRPC (l'equivalente del SecurityContext)
        Context contesto = Context.current().withValue(UTENTE, utente);
        return Contexts.interceptCall(contesto, chiamata, intestazioni, prossimo);
    }

    /** Chiude la chiamata con l'errore, senza passarla al servizio. */
    private static <Q, R> ServerCall.Listener<Q> chiudi(ServerCall<Q, R> chiamata, Status stato) {
        chiamata.close(stato, new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
