package it.ginvault.produzione;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * - Orologio: un bean, così i test possono fissare l'istante.
 * - @EnableScheduling: attiva il giro periodico di PubblicatoreOutbox.
 */
@Configuration
@EnableScheduling
public class Configurazione {

    @Bean
    Clock orologio() {
        return Clock.systemUTC();
    }
}
