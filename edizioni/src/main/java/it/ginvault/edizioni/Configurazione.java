package it.ginvault.edizioni;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * - @EnableScheduling: attiva il giro periodico di PubblicatoreOutbox.
 * - Orologio (UTC) per pubblicato_il nell'outbox: un bean, così i test possono fissarlo.
 */
@Configuration
@EnableScheduling
public class Configurazione {

    @Bean
    Clock orologio() {
        return Clock.systemUTC();
    }
}
