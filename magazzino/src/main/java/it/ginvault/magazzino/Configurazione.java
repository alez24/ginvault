package it.ginvault.magazzino;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * - Orologio con il fuso di Roma: "oggi" per le date di consegna (una consegna
 *   delle 23:30 è di oggi, non di domani come sarebbe in UTC). Un bean, così i
 *   test possono fissare la data.
 * - @EnableScheduling: attiva il giro periodico di PubblicatoreOutbox.
 */
@Configuration
@EnableScheduling
public class Configurazione {

    @Bean
    Clock orologio() {
        return Clock.system(ZoneId.of("Europe/Rome"));
    }
}
