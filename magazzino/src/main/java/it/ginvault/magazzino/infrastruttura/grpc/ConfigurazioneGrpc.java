package it.ginvault.magazzino.infrastruttura.grpc;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import it.ginvault.giacenze.v1.GiacenzeGrpc;
import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Collega i pezzi del server gRPC: servizio, controllo del JWT, server.
 *
 * I ruoli per metodo sono l'equivalente dei @PreAuthorize dei controller:
 *  - Disponibilita: chi guarda i lotti (Marco, Elena, admin);
 *  - RiservaLotto:  solo chi decide il piano di produzione (Marco, admin).
 */
@Configuration
public class ConfigurazioneGrpc {

    @Bean
    ServizioGiacenze servizioGiacenze(ServizioApprovvigionamento servizio) {
        return new ServizioGiacenze(servizio);
    }

    @Bean
    AutenticazioneGrpc autenticazioneGrpc(JwtDecoder decodificatore, JwtAuthenticationConverter convertitore) {
        return new AutenticazioneGrpc(decodificatore, convertitore, Map.of(
                GiacenzeGrpc.getDisponibilitaMethod().getFullMethodName(),
                Set.of("ROLE_DISTILLATORE", "ROLE_RESP_ACQUISTI", "ROLE_ADMIN"),
                GiacenzeGrpc.getRiservaLottoMethod().getFullMethodName(),
                Set.of("ROLE_DISTILLATORE", "ROLE_ADMIN")));
    }

    @Bean
    ServerGiacenze serverGiacenze(@Value("${magazzino.grpc.porta}") int porta,
                                  @Value("${magazzino.grpc.attesa-spegnimento}") Duration attesaSpegnimento,
                                  ServizioGiacenze servizio, AutenticazioneGrpc autenticazione) {
        return new ServerGiacenze(porta, attesaSpegnimento, servizio, autenticazione);
    }
}
