package it.ginvault.edizioni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.jayway.jsonpath.JsonPath;

import it.ginvault.edizioni.applicazione.ServizioPrenotazioni;
import it.ginvault.edizioni.dominio.Acquirente;
import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;
import it.ginvault.edizioni.sicurezza.GatewayDiProva;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Le prenotazioni dall'inizio alla fine, con PostgreSQL vero (Testcontainers):
 *  - Giulia, socia, prenota 2 bottiglie in prelazione e ritrova la prenotazione;
 *  - un cliente in prelazione riceve 409; dopo l'apertura della vendita
 *    prenota dalla quota ONLINE, al prezzo online;
 *  - CONCORRENZA: molte prenotazioni nello stesso istante non superano mai le
 *    bottiglie libere, e due richieste della stessa persona non superano il
 *    limite per persona (il blocco della riga dell'edizione le mette in fila).
 * Le edizioni si salvano direttamente con il repository (la creazione con
 * Ricette è in EdizioniIntegrazioneTest). Kafka c'è perché l'applicazione ha
 * consumer e outbox: qui non si guardano i messaggi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PrenotazioniIntegrazioneTest {

    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";

    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigurazioneDiProva {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.0");
        }

        @Bean
        NewTopic edizioniEventi() {
            return TopicBuilder.name("edizioni.eventi").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic produzioneEventi() {
            return TopicBuilder.name("produzione.eventi").partitions(3).replicas(1).build();
        }

        /** Gli esiti di Pagamenti, letti dal secondo listener (provato in SagaPagamentoTest): qui restano vuoti. */
        @Bean
        NewTopic pagamentiEsiti() {
            return TopicBuilder.name("pagamenti.esiti").partitions(3).replicas(1).build();
        }
    }

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();

    @DynamicPropertySource
    static void puntaAllaJwksDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
    }

    @AfterAll
    static void ferma() {
        GATEWAY.ferma();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EdizioneRepository edizioni;

    @Autowired
    private ServizioPrenotazioni prenotazioni;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- la storia di Giulia

    @Test
    void giuliaPrenota2BottiglieInPrelazioneERitrovaLaPrenotazione() throws Exception {
        UUID estate = edizione(300, 150, 150);
        String giulia = persona("github");

        String risposta = prenota(giulia, "SOCIO", estate, 2)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/prenotazioni/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.stato").value("IN_ATTESA_PAGAMENTO"))
                .andExpect(jsonPath("$.canale").value("SOCI"))
                .andExpect(jsonPath("$.bottiglie").value(2))
                .andExpect(jsonPath("$.prezzoPerBottiglia").value(45.00))
                .andExpect(jsonPath("$.totale").value(90.00))
                .andExpect(jsonPath("$.edizione.id").value(estate.toString()))
                .andExpect(jsonPath("$.edizione.tiratura").value(600))
                .andExpect(jsonPath("$.ritiro.occasione").value(matchesPattern("masterclass d(’|i ).+")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(risposta, "$.id");

        // Nel database: la prenotazione e le 2 bottiglie tolte dalla quota SOCI, nello stesso commit
        assertThat(jdbc.queryForObject("select stato from prenotazione where id = ?::uuid", String.class, id))
                .isEqualTo("IN_ATTESA_PAGAMENTO");
        assertThat(prenotate(estate, Canale.SOCI)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select scade_il - prenotata_il from prenotazione where id = ?::uuid",
                String.class, id)).isEqualTo("00:15:00");

        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni/{id}", id))).andExpect(status().isOk());
        mvc.perform(come(persona("google"), "CLIENTE", get("/prenotazioni/{id}", id))).andExpect(status().isNotFound());

        // L'edizione vista dai soci: in prelazione, con le prenotate e l'annuncio
        mvc.perform(come(giulia, "SOCIO", get("/edizioni/{id}", estate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("IN_PRELAZIONE"))
                .andExpect(jsonPath("$.prenotate.SOCI").value(2))
                .andExpect(jsonPath("$.tiraturaAnnunciata").value(600))
                .andExpect(jsonPath("$.quoteAnnunciate.SOCI").value(300));

        // Una terza bottiglia no: il massimo per socio in prelazione è 2
        prenota(giulia, "SOCIO", estate, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("LIMITE_PER_PERSONA"));
        assertThat(prenotate(estate, Canale.SOCI)).isEqualTo(2);
    }

    @Test
    void unClienteAspettaLaFineDellaPrelazionePoiPrenotaOnline() throws Exception {
        UUID estate = edizione(300, 150, 150);
        String luca = persona("google");

        prenota(luca, "CLIENTE", estate, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Prenotazione non possibile"))
                .andExpect(jsonPath("$.motivo").value("PRELAZIONE_DEI_SOCI"));
        assertThat(prenotate(estate, Canale.ONLINE)).isZero();

        mvc.perform(come(persona("github"), "DISTILLATORE", post("/edizioni/{id}/vendita", estate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("IN_VENDITA"));

        prenota(luca, "CLIENTE", estate, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canale").value("ONLINE"))
                .andExpect(jsonPath("$.prezzoPerBottiglia").value(52.00))
                .andExpect(jsonPath("$.totale").value(104.00));
        assertThat(prenotate(estate, Canale.ONLINE)).isEqualTo(2);
        assertThat(prenotate(estate, Canale.SOCI)).isZero();
    }

    @Test
    void leRegoleDiVenditaVengonoDalClubEDallaConfigurazione() throws Exception {
        mvc.perform(come(persona("github"), "SOCIO", get("/regole-di-vendita")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.giorniDiPrelazione").value(7))
                .andExpect(jsonPath("$.bottigliePerSocio").value(2))
                .andExpect(jsonPath("$.bottigliePerCliente").value(6))
                .andExpect(jsonPath("$.prezzoSoci").value(45.00))
                .andExpect(jsonPath("$.prezzoOnline").value(52.00))
                .andExpect(jsonPath("$.minutiPerPagare").value(15))
                .andExpect(jsonPath("$.metodiDiPagamento[0]").value("pm_card_visa"))
                .andExpect(jsonPath("$.metodiDiPagamento[1]").value("pm_card_chargeDeclined"));
    }

    // ---------------------------------------------------------------- concorrenza

    @Test
    void dodiciClientiInsiemePerCinqueBottiglieOnline() throws Exception {
        UUID edizione = edizione(595, 0, 5);
        apriLaVendita(edizione);
        List<Callable<String>> richieste = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Acquirente cliente = new Acquirente(persona("google"), false);
            richieste.add(() -> esito(() -> prenotazioni.prenota(edizione, cliente, 1)));
        }

        List<String> esiti = tuttiInsieme(richieste);

        assertThat(esiti).filteredOn("OK"::equals).hasSize(5);
        assertThat(esiti).filteredOn("QuotaEsaurita"::equals).hasSize(7);
        assertThat(prenotate(edizione, Canale.ONLINE)).isEqualTo(5);
        assertThat(righeDiPrenotazione(edizione)).isEqualTo(5);
    }

    @Test
    void quattroRichiesteInsiemeDelloStessoSocioNonSuperanoLe2Bottiglie() throws Exception {
        UUID edizione = edizione(300, 150, 150);
        Acquirente giulia = new Acquirente(persona("github"), true);
        List<Callable<String>> richieste = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            richieste.add(() -> esito(() -> prenotazioni.prenota(edizione, giulia, 1)));
        }

        List<String> esiti = tuttiInsieme(richieste);

        assertThat(esiti).filteredOn("OK"::equals).hasSize(2);
        assertThat(esiti).filteredOn("PrenotazioneNonPossibile"::equals).hasSize(2);
        assertThat(prenotate(edizione, Canale.SOCI)).isEqualTo(2);
        assertThat(righeDiPrenotazione(edizione)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- supporto

    /** Un'edizione da 600 bottiglie (12 kg a 20 g), salvata in prelazione, con le quote indicate. */
    private UUID edizione(int soci, int bar, int online) {
        Map<Canale, Integer> quote = new EnumMap<>(Canale.class);
        quote.put(Canale.SOCI, soci);
        quote.put(Canale.BAR, bar);
        quote.put(Canale.ONLINE, online);
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 2, "Lavanda",
                new BigDecimal("20"), null), 2, new BigDecimal("12"), ORIGINE, quote, Duration.ofDays(7));
        edizioni.save(edizione);
        return edizione.id();
    }

    private void apriLaVendita(UUID edizione) throws Exception {
        mvc.perform(come(persona("github"), "DISTILLATORE", post("/edizioni/{id}/vendita", edizione)))
                .andExpect(status().isOk());
    }

    /** Un "sub" nuovo per ogni persona: i test non si mescolano le prenotazioni. */
    private static String persona(String provider) {
        return provider + ":" + UUID.randomUUID();
    }

    private ResultActions prenota(String sub, String ruolo, UUID edizione, int bottiglie) throws Exception {
        return mvc.perform(come(sub, ruolo, post("/edizioni/{id}/prenotazioni", edizione)
                .contentType(MediaType.APPLICATION_JSON).content("{\"bottiglie\": " + bottiglie + "}")));
    }

    private static MockHttpServletRequestBuilder come(String sub, String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.tokenPer(sub, ruolo));
    }

    private int prenotate(UUID edizione, Canale canale) {
        return jdbc.queryForObject("select prenotate from quota where edizione_id = ? and canale = ?", Integer.class,
                edizione, canale.name());
    }

    private int righeDiPrenotazione(UUID edizione) {
        return jdbc.queryForObject("select count(*) from prenotazione where edizione_id = ?", Integer.class, edizione);
    }

    /** "OK" se la prenotazione riesce, altrimenti il nome dell'eccezione. */
    private static String esito(Runnable prenotazione) {
        try {
            prenotazione.run();
            return "OK";
        } catch (RuntimeException rifiuto) {
            return rifiuto.getClass().getSimpleName();
        }
    }

    /** Tutte le richieste partono nello stesso istante, ognuna nel suo thread (e nella sua transazione). */
    private static List<String> tuttiInsieme(List<Callable<String>> richieste)
            throws InterruptedException, ExecutionException {
        ExecutorService thread = Executors.newFixedThreadPool(richieste.size());
        CountDownLatch via = new CountDownLatch(1);
        try {
            List<Future<String>> futuri = new ArrayList<>();
            for (Callable<String> richiesta : richieste) {
                futuri.add(thread.submit(() -> {
                    via.await();
                    return richiesta.call();
                }));
            }
            via.countDown();
            List<String> esiti = new ArrayList<>();
            for (Future<String> futuro : futuri) {
                esiti.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return esiti;
        } catch (TimeoutException bloccato) {
            throw new AssertionError("Le prenotazioni non sono finite entro 30 secondi", bloccato);
        } finally {
            thread.shutdownNow();
        }
    }
}
