package it.ginvault.magazzino.infrastruttura.grpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Il server gRPC del magazzino, accanto a Tomcat: HTTP sulla 8084 (REST per
 * il gateway), gRPC sulla 9084 (Giacenze per Produzione).
 *
 * Lo accende e lo spegne Spring (SmartLifecycle): parte quando il contesto è
 * pronto, e allo spegnimento (SIGTERM di docker stop o di Kubernetes) smette
 * di accettare chiamate nuove e lascia finire quelle in corso, per al massimo
 * attesaSpegnimento; poi chiude tutto.
 *
 * In chiaro, senza TLS: è sulla rete interna (Compose "backend"; su
 * Kubernetes la NetworkPolicy fa entrare sulla 9084 solo Produzione) e ogni
 * chiamata porta comunque il JWT dell'utente, controllato da AutenticazioneGrpc.
 */
public class ServerGiacenze implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ServerGiacenze.class);

    private final int portaRichiesta;
    private final Duration attesaSpegnimento;
    private final BindableService servizio;
    private final ServerInterceptor autenticazione;

    private volatile Server server;

    /** @param portaRichiesta 0 = una porta libera qualunque (nei test) */
    public ServerGiacenze(int portaRichiesta, Duration attesaSpegnimento, BindableService servizio,
                          ServerInterceptor autenticazione) {
        this.portaRichiesta = portaRichiesta;
        this.attesaSpegnimento = attesaSpegnimento;
        this.servizio = servizio;
        this.autenticazione = autenticazione;
    }

    @Override
    public void start() {
        try {
            server = Grpc.newServerBuilderForPort(portaRichiesta, InsecureServerCredentials.create())
                    // ogni chiamata passa prima dal controllo del JWT
                    .addService(ServerInterceptors.intercept(servizio, autenticazione))
                    .build()
                    .start();
        } catch (IOException errore) {
            // Porta occupata: meglio non partire che partire senza gRPC
            throw new UncheckedIOException("Server gRPC non avviato sulla porta " + portaRichiesta, errore);
        }
        log.info("Server gRPC Giacenze in ascolto sulla porta {}", server.getPort());
    }

    @Override
    public void stop() {
        Server attivo = server;
        if (attivo == null) {
            return;
        }
        attivo.shutdown();
        try {
            if (!attivo.awaitTermination(attesaSpegnimento.toMillis(), TimeUnit.MILLISECONDS)) {
                attivo.shutdownNow();
            }
        } catch (InterruptedException interrotto) {
            attivo.shutdownNow();
            Thread.currentThread().interrupt();
        }
        server = null;
        log.info("Server gRPC Giacenze spento");
    }

    @Override
    public boolean isRunning() {
        Server attivo = server;
        return attivo != null && !attivo.isShutdown();
    }

    /** La porta vera (con 0 la sceglie il sistema operativo). */
    public int porta() {
        Server attivo = server;
        if (attivo == null) {
            throw new IllegalStateException("Server gRPC non avviato");
        }
        return attivo.getPort();
    }
}
