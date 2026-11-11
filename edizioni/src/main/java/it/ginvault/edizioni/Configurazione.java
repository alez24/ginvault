package it.ginvault.edizioni;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import it.ginvault.edizioni.applicazione.MetodiDiPagamento;
import it.ginvault.edizioni.dominio.Club;
import it.ginvault.edizioni.dominio.RegoleDiVendita;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * - @EnableScheduling: attiva i giri periodici di PubblicatoreOutbox e di
 *   ScadenzaPrenotazioni.
 * - Orologio (UTC) per pubblicato_il nell'outbox e per la prelazione: un bean,
 *   così i test possono fissarlo.
 * - Regole di vendita: prelazione e bottiglie per socio dal Club, il resto da
 *   application.yml (edizioni.vendita.*).
 */
@Configuration
@EnableScheduling
public class Configurazione {

    @Bean
    Clock orologio() {
        return Clock.systemUTC();
    }

    @Bean
    RegoleDiVendita regoleDiVendita(Club club,
                                    @Value("${edizioni.vendita.bottiglie-per-cliente}") int bottigliePerCliente,
                                    @Value("${edizioni.vendita.prezzo-soci}") BigDecimal prezzoSoci,
                                    @Value("${edizioni.vendita.prezzo-online}") BigDecimal prezzoOnline,
                                    @Value("${edizioni.vendita.tempo-per-pagare}") Duration tempoPerPagare) {
        return new RegoleDiVendita(club.durataPrelazione(), club.bottigliePerSocio(), bottigliePerCliente,
                prezzoSoci, prezzoOnline, tempoPerPagare);
    }

    /** Le carte di prova di Stripe del menu (edizioni.vendita.metodi-di-pagamento, separate da virgole). */
    @Bean
    MetodiDiPagamento metodiDiPagamento(@Value("${edizioni.vendita.metodi-di-pagamento}") List<String> metodi) {
        return new MetodiDiPagamento(metodi);
    }
}
