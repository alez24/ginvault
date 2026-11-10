package it.ginvault.produzione.infrastruttura.magazzino;

import java.time.Duration;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;

import it.ginvault.produzione.applicazione.Giacenze;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Il canale gRPC verso il magazzino e la porta Giacenze che lo usa.
 *
 * Un ManagedChannel è una connessione HTTP/2 di lunga durata, condivisa da
 * tutte le richieste (le chiamate viaggiano in parallelo sulla stessa
 * connessione): se ne crea UNO all'avvio. Non si collega subito ma alla prima
 * chiamata, quindi Produzione parte anche con il magazzino spento; se il
 * magazzino cade si ricollega da solo.
 *
 * In chiaro (InsecureChannelCredentials): rete interna, vedi ServerGiacenze
 * del magazzino. Allo spegnimento Spring chiude il canale (shutdownNow).
 */
@Configuration
public class ConfigurazioneGiacenze {

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel canaleMagazzino(@Value("${produzione.magazzino.indirizzo}") String indirizzo) {
        return Grpc.newChannelBuilder(indirizzo, InsecureChannelCredentials.create())
                .intercept(new InoltroJwt())
                .userAgent("ginvault-produzione")
                .build();
    }

    @Bean
    Giacenze giacenze(ManagedChannel canaleMagazzino,
                      @Value("${produzione.magazzino.deadline}") Duration deadline) {
        return new ClientGiacenze(canaleMagazzino, deadline);
    }
}
