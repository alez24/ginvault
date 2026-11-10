package it.ginvault.edizioni;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;

import it.ginvault.edizioni.dominio.TiraturaRidotta;
import it.ginvault.edizioni.sicurezza.GatewayDiProva;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Edizioni dall'inizio alla fine: creazione validata da Ricette via REST e
 * ricalcolo della tiratura.
 *  - Edizioni è l'applicazione vera, con PostgreSQL vero (Testcontainers);
 *  - Ricette è WireMock: un server HTTP vero su una porta casuale, che risponde
 *    con il JSON di Ricette (FormulazioneVista) o con errori e ritardi.
 * Si verificano le risposte a Marco, le chiamate fatte a Ricette (con il suo
 * JWT inoltrato), cosa finisce, o non finisce, nel database e l'evento
 * TiraturaRidotta (registrato con @RecordApplicationEvents, log catturato).
 * Con un Kafka vero: EdizioneCreata passa dall'outbox a edizioni.eventi, e
 * un'edizione non creata non lascia eventi.
 */
@SpringBootTest(properties = "edizioni.outbox.intervallo-ms=200")
@AutoConfigureMockMvc
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
class EdizioniIntegrazioneTest {

    private static final String TOPIC = "edizioni.eventi";
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";

    /**
     * Container come bean: Spring li ferma DOPO il publisher dell'outbox e il
     * producer Kafka (con @Container si fermerebbero a fine classe, mentre il
     * contesto in cache, con il suo publisher, resta vivo fino alla fine dei test).
     */
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

        /** Il topic come lo crea il Job kafka-topic: 3 partizioni. */
        @Bean
        NewTopic edizioniEventi() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
    }

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final WireMockServer RICETTE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        RICETTE.start();
    }

    /** Il JWT di Marco: deve arrivare identico anche a Ricette (token relay). */
    private static final String JWT_DI_MARCO = GATEWAY.token("DISTILLATORE");

    @DynamicPropertySource
    static void collegaGatewayERicetteDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
        registro.add("RICETTE_URI", RICETTE::baseUrl);
    }

    @AfterAll
    static void ferma() {
        RICETTE.stop();
        GATEWAY.ferma();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private ObjectMapper json;

    /** Gli eventi pubblicati durante il test in corso. */
    @Autowired
    private ApplicationEvents eventi;

    private UUID formulazione;

    @BeforeEach
    void nuovaFormulazione() {
        RICETTE.resetAll();
        formulazione = UUID.randomUUID();
    }

    // ---------------------------------------------------------------- caso ok

    @Test
    void laFormulazioneSceltaDiventaUnEdizione() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())));

        String corpo = creaEdizione(2)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/edizioni/")))
                .andExpect(jsonPath("$.formulazione.id").value(formulazione.toString()))
                .andExpect(jsonPath("$.formulazione.versione").value(2))
                .andExpect(jsonPath("$.botanicaDiStagione").value("lavanda"))
                .andExpect(jsonPath("$.grammiPerBottiglia").value(20.0))
                .andExpect(jsonPath("$.quote.ONLINE").value(150))
                .andReturn().getResponse().getContentAsString();
        String edizione = JsonPath.read(corpo, "$.id");

        // A Ricette: prima la lettura, poi l'utilizzo con l'id della nuova edizione,
        // entrambe con il JWT di Marco
        RICETTE.verify(getRequestedFor(urlEqualTo("/formulazioni/" + formulazione))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + JWT_DI_MARCO)));
        RICETTE.verify(postRequestedFor(urlEqualTo("/formulazioni/" + formulazione + "/utilizzo"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + JWT_DI_MARCO))
                .withRequestBody(matchingJsonPath("$.edizioneId", equalTo(edizione))));

        // Nel database di Edizioni: solo il riferimento (id + versione) e le tre quote
        assertThat(jdbc.queryForMap("select formulazione_id, formulazione_versione from edizione where id = ?",
                UUID.fromString(edizione)))
                .containsEntry("formulazione_id", formulazione)
                .containsEntry("formulazione_versione", 2);
        assertThat(jdbc.queryForObject("select count(*) from quota where edizione_id = ?", Integer.class,
                UUID.fromString(edizione))).isEqualTo(3);

        // ...e si rilegge
        mvc.perform(MockMvcRequestBuilders.get("/edizioni/{id}", edizione)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote.SOCI").value(300));
    }

    @Test
    void lEdizioneCreataArrivaSuKafkaDallOutbox() throws Exception {
        String edizione = edizioneDa600Bottiglie();

        // Nell'outbox una riga, con chiave = id dell'edizione, poi pubblicata
        UUID eventId = jdbc.queryForObject("select event_id from outbox where chiave = ? and tipo = 'EdizioneCreata'",
                UUID.class, edizione);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select pubblicato_il is not null from outbox where event_id = ?", Boolean.class, eventId));

        ConsumerRecord<String, String> messaggio = leggiDaKafka(eventId).getFirst();
        assertThat(messaggio.key()).isEqualTo(edizione);
        assertThat(messaggio.headers().lastHeader("__TypeId__")).isNull();
        JsonNode busta = json.readTree(messaggio.value());
        assertThat(busta.path("tipo").asText()).isEqualTo("EdizioneCreata");
        assertThat(busta.path("versione").asInt()).isEqualTo(1);
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("edizioneId").asText()).isEqualTo(edizione);
        assertThat(payload.path("formulazione").path("id").asText()).isEqualTo(formulazione.toString());
        assertThat(payload.path("formulazione").path("versione").asInt()).isEqualTo(2);
        assertThat(payload.path("botanicaDiStagione").asText()).isEqualTo("lavanda");
        assertThat(payload.path("grammiPerBottiglia").decimalValue()).isEqualByComparingTo("20");
        assertThat(payload.path("origineBotanica").asText()).isEqualTo(ORIGINE);
        assertThat(payload.path("kgPrevisti").decimalValue()).isEqualByComparingTo("12");
        assertThat(payload.path("tiratura").asInt()).isEqualTo(600);
        assertThat(payload.path("quote").path("SOCI").asInt()).isEqualTo(300);
    }

    @Test
    void senzaOrigineRisponde400ERicetteNonVieneChiamato() throws Exception {
        String senzaOrigine = nuovaEdizioneJson(2).replace("\"origineBotanica\": \"" + ORIGINE + "\",", "");

        mvc.perform(MockMvcRequestBuilders.post("/edizioni")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(senzaOrigine))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campi[0].campo").value("origineBotanica"));

        RICETTE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void dietroGatewayEIngressLaLocationPuntaAllIndirizzoPubblico() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())));

        mvc.perform(MockMvcRequestBuilders.post("/edizioni")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO)
                        // Su Kubernetes il gateway accoda i suoi valori a quelli dell'Ingress:
                        // Spring usa il primo di ogni elenco
                        .header("X-Forwarded-Host", "localhost,localhost")
                        .header("X-Forwarded-Proto", "http,http")
                        .header("X-Forwarded-Port", "80,80")
                        .header("X-Forwarded-Prefix", "/api/edizioni")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nuovaEdizioneJson(2)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        matchesPattern("http://localhost/api/edizioni/edizioni/[0-9a-f-]{36}")));
    }

    // ---------------------------------------------------------------- 422: inesistente o non scelta

    @Test
    void formulazioneInesistenteRisponde422() throws Exception {
        ricetteRispondeAllaLettura(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\": 404, \"title\": \"Formulazione non trovata\"}"));

        creaEdizione(2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("non esiste in Ricette")));

        nessunUtilizzoENessunaEdizione();
    }

    @Test
    void formulazioneNonSceltaRisponde422() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("IN_PROVA", null, null)));

        creaEdizione(2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("non è ancora stata scelta dal panel")));

        nessunUtilizzoENessunaEdizione();
    }

    @Test
    void versioneDiversaDaQuellaSceltaRisponde422() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));

        creaEdizione(3)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("La versione 3 non è quella scelta dal panel (2)"));

        nessunUtilizzoENessunaEdizione();
    }

    // ---------------------------------------------------------------- 409: già usata

    @Test
    void formulazioneGiaUsataRisponde409() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())));

        creaEdizione(2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("non si riusa mai")));

        nessunUtilizzoENessunaEdizione();
    }

    @Test
    void seRicetteRifiutaLUtilizzoRisponde409ENonSalva() throws Exception {
        // La GET dice SCELTA, ma nel frattempo un'altra edizione l'ha presa
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\": 409, \"detail\": \"già usata per l'edizione X\"}"));

        creaEdizione(2).andExpect(status().isConflict());

        RICETTE.verify(1, postRequestedFor(urlEqualTo("/formulazioni/" + formulazione + "/utilizzo")));
        assertThat(edizioniCon(formulazione)).isZero();
    }

    @Test
    void reteDiSicurezzaIlVincoloUniqueImpedisceLaSecondaEdizione() throws Exception {
        // Ricette "dimentica" l'utilizzo (es. database ricreato) e dice SCELTA due volte
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())));

        creaEdizione(2).andExpect(status().isCreated());
        creaEdizione(2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Esiste già un'edizione")));

        assertThat(edizioniCon(formulazione)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 503: Ricette lento o spento

    @Test
    void seRicetteNonRispondeEntro2SecondiRisponde503() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)).withFixedDelay(4_000));

        long inizio = System.nanoTime();
        creaEdizione(2)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.title").value("Ricette non disponibile"))
                .andExpect(jsonPath("$.detail").value(containsString("più lento di 2 secondi")))
                .andExpect(jsonPath("$.trace").doesNotExist());
        Duration attesa = Duration.ofNanos(System.nanoTime() - inizio);

        assertThat(attesa).isBetween(Duration.ofMillis(1_900), Duration.ofMillis(3_500));
        nessunUtilizzoENessunaEdizione();
    }

    @Test
    void seRicetteNonConfermaLUtilizzoRisponde503ENonSalva() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())).withFixedDelay(4_000));

        creaEdizione(2)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(containsString("non ha confermato l'utilizzo")));

        assertThat(edizioniCon(formulazione)).isZero();
    }

    // ---------------------------------------------------------------- ricalcolo

    @Test
    void laLavandaArrivaScarsaEDa600SiScendeA540SenzaChiamareRicette(CapturedOutput log) throws Exception {
        String edizione = edizioneDa600Bottiglie();
        RICETTE.resetAll();   // da qui Ricette non serve: i grammi per bottiglia sono nell'Edizione

        ricalcola(edizione, "10.8")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiratura").value(540))
                .andExpect(jsonPath("$.quote.SOCI").value(300))
                .andExpect(jsonPath("$.quote.BAR").value(150))
                .andExpect(jsonPath("$.quote.ONLINE").value(90));

        RICETTE.verify(0, anyRequestedFor(anyUrl()));
        UUID id = UUID.fromString(edizione);
        assertThat(jdbc.queryForObject("select tiratura from edizione where id = ?", Integer.class, id))
                .isEqualTo(540);
        assertThat(jdbc.queryForObject("select bottiglie from quota where edizione_id = ? and canale = 'ONLINE'",
                Integer.class, id)).isEqualTo(90);
        // Evento di dominio pubblicato (in-process) e loggato dopo il commit
        assertThat(eventi.stream(TiraturaRidotta.class)).singleElement().satisfies(evento -> {
            assertThat(evento.edizioneId()).isEqualTo(id);
            assertThat(evento.tiraturaVecchia()).isEqualTo(600);
            assertThat(evento.tiraturaNuova()).isEqualTo(540);
        });
        assertThat(log).contains("Evento TiraturaRidotta: edizione " + edizione
                + " da 600 a 540 bottiglie, nuove quote {SOCI=300, BAR=150, ONLINE=90}");
    }

    @Test
    void unRicalcoloCheToccherebbeSociRisponde409ENonCambiaNulla() throws Exception {
        String edizione = edizioneDa600Bottiglie();

        ricalcola(edizione, "5")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Tiratura non riducibile"))
                .andExpect(jsonPath("$.detail").value(containsString("la quota SOCI (300) non si riduce mai")));

        assertThat(jdbc.queryForObject("select tiratura from edizione where id = ?", Integer.class,
                UUID.fromString(edizione))).isEqualTo(600);
        assertThat(eventi.stream(TiraturaRidotta.class)).isEmpty();
    }

    // ---------------------------------------------------------------- elenco

    @Test
    void lElencoParteDallEdizionePiuRecenteESenzaChiamareRicette() throws Exception {
        String prima = edizioneDa600Bottiglie();
        formulazione = UUID.randomUUID();   // ogni edizione ha la sua formulazione
        String seconda = edizioneDa600Bottiglie();
        RICETTE.resetAll();

        String corpo = mvc.perform(MockMvcRequestBuilders.get("/edizioni")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Nel database ci sono anche le edizioni degli altri test: conta l'ordine relativo
        List<String> id = JsonPath.read(corpo, "$[*].id");
        assertThat(id).contains(prima, seconda);
        assertThat(id.indexOf(seconda)).isLessThan(id.indexOf(prima));
        RICETTE.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------- 401 / 403

    @Test
    void senzaTokenOSenzaIlRuoloGiustoRicetteNonVieneNemmenoChiamato() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/edizioni").contentType(MediaType.APPLICATION_JSON)
                        .content(nuovaEdizioneJson(2)))
                .andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/edizioni").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token("ADMIN"))
                        .content(nuovaEdizioneJson(2)))
                .andExpect(status().isForbidden());

        RICETTE.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------------------------------------------------------------- supporto

    private ResultActions creaEdizione(int versione) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/edizioni")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO)
                .contentType(MediaType.APPLICATION_JSON)
                .content(nuovaEdizioneJson(versione)));
    }

    private String edizioneDa600Bottiglie() throws Exception {
        ricetteRispondeAllaLettura(okJson(formulazioneJson("SCELTA", 2, null)));
        ricetteRispondeAllUtilizzo(okJson(formulazioneJson("USATA", 2, UUID.randomUUID())));
        String corpo = creaEdizione(2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tiratura").value(600))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private ResultActions ricalcola(String edizione, String kg) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/edizioni/{id}/ricalcolo", edizione)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"botanicaDisponibileKg\": " + kg + "}"));
    }

    private String nuovaEdizioneJson(int versione) {
        return """
                {"formulazione": {"id": "%s", "versione": %d},
                 "botanicaDisponibileKg": 12, "origineBotanica": "Alta Val Varaita (Alpi Cozie)",
                 "quote": {"SOCI": 300, "BAR": 150, "ONLINE": 150}}
                """.formatted(formulazione, versione);
    }

    private void ricetteRispondeAllaLettura(ResponseDefinitionBuilder risposta) {
        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + formulazione)).willReturn(risposta));
    }

    private void ricetteRispondeAllUtilizzo(ResponseDefinitionBuilder risposta) {
        RICETTE.stubFor(post(urlEqualTo("/formulazioni/" + formulazione + "/utilizzo")).willReturn(risposta));
    }

    private void nessunUtilizzoENessunaEdizione() {
        RICETTE.verify(0, postRequestedFor(urlEqualTo("/formulazioni/" + formulazione + "/utilizzo")));
        assertThat(edizioniCon(formulazione)).isZero();
        // ...e nessun EdizioneCreata nell'outbox: un'edizione non creata non si annuncia
        assertThat(jdbc.queryForObject("select count(*) from outbox where tipo = 'EdizioneCreata'"
                + " and messaggio::jsonb -> 'payload' -> 'formulazione' ->> 'id' = ?", Integer.class,
                formulazione.toString())).isZero();
    }

    /** Legge edizioni.eventi dall'inizio, con un consumer group nuovo, finché trova l'evento. */
    private List<ConsumerRecord<String, String>> leggiDaKafka(UUID eventId) {
        Map<String, Object> configurazione = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> trovati = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configurazione)) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(20)).pollInSameThread().until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(messaggio -> {
                    if (messaggio.value().contains(eventId.toString())) {
                        trovati.add(messaggio);
                    }
                });
                return !trovati.isEmpty();
            });
        }
        return trovati;
    }

    private int edizioniCon(UUID formulazioneId) {
        return jdbc.queryForObject("select count(*) from edizione where formulazione_id = ?", Integer.class,
                formulazioneId);
    }

    /**
     * Il JSON che Ricette restituisce (FormulazioneVista), con i campi che
     * Edizioni ignora (nome, esito, registrataIl): la storia di Marco, tre prove
     * con la lavanda, la seconda da 20 g per bottiglia.
     */
    private String formulazioneJson(String stato, Integer versioneScelta, UUID edizioneId) {
        String esito1 = versioneScelta == null ? "IN_ASSAGGIO" : "ARCHIVIATA";
        String esito2 = versioneScelta == null ? "IN_ASSAGGIO" : "SCELTA";
        return """
                {"id": "%s", "nome": "Edizione d'estate", "botanicaDiStagione": "lavanda",
                 "stato": "%s", "versioneScelta": %s, "edizioneId": %s,
                 "prove": [
                   {"versione": 1, "esito": "%s", "registrataIl": "2026-06-01T09:00:00Z",
                    "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 18.00},
                             {"botanica": "ginepro", "grammiPerBottiglia": 12.00}]},
                   {"versione": 2, "esito": "%s", "registrataIl": "2026-06-02T09:00:00Z",
                    "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 20.00},
                             {"botanica": "ginepro", "grammiPerBottiglia": 10.00}]},
                   {"versione": 3, "esito": "%s", "registrataIl": "2026-06-03T09:00:00Z",
                    "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 24.00},
                             {"botanica": "ginepro", "grammiPerBottiglia": 9.00}]}]}
                """.formatted(formulazione, stato, versioneScelta, edizioneId == null ? "null" : "\"" + edizioneId + "\"",
                esito1, esito2, esito1);
    }
}
