package it.ginvault.edizioni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import it.ginvault.edizioni.applicazione.ServizioPrenotazioni;
import it.ginvault.edizioni.dominio.Acquirente;
import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;
import it.ginvault.edizioni.sicurezza.GatewayDiProva;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * La SAGA del pagamento dall'inizio alla fine, con PostgreSQL e Kafka veri
 * (Testcontainers). Pagamenti qui non c'è: la sua parte la fa il test, che
 * legge i comandi da pagamenti.comandi e scrive gli esiti su pagamenti.esiti
 * come li scrive l'outbox di Pagamenti (docs/eventi.md).
 *
 *  - la storia di Giulia: paga con pm_card_visa e, dopo 111 bottiglie già
 *    numerate, le sue sono la 112 e la 113 (PrenotazioneConfermata);
 *  - COMPENSAZIONE: con pm_card_chargeDeclined la prenotazione si annulla e
 *    le bottiglie tornano libere (PrenotazioneAnnullata);
 *  - TIMEOUT: a tempo finito la scadenza annulla; il pagamento che arriva dopo
 *    si restituisce (RichiestaRimborso, poi RimborsoEseguito);
 *  - IDEMPOTENZA: un esito ripetuto, con lo stesso eventId o con uno nuovo,
 *    non cambia niente;
 *  - un esito per una prenotazione che non esiste va subito in edizioni.dlt;
 *  - CONCORRENZA: 20 conferme nello stesso istante prendono i numeri da 1 a
 *    40, senza buchi né doppioni.
 *
 * Scadenze controllate ogni 300 ms; tentativi del consumer più rapidi.
 */
@SpringBootTest(properties = {
        "edizioni.outbox.intervallo-ms=200",
        "edizioni.vendita.controllo-scadenze-ms=300",
        "edizioni.consumer.tentativi=3",
        "edizioni.consumer.attesa-iniziale=300ms",
        "edizioni.consumer.attesa-massima=1s"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SagaPagamentoTest {

    private static final String COMANDI = "pagamenti.comandi";
    private static final String ESITI = "pagamenti.esiti";
    private static final String EDIZIONI = "edizioni.eventi";
    private static final String DEAD_LETTER = "edizioni.dlt";

    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String RIFIUTATA = "La banca ha rifiutato la carta.";
    private static final Duration ENTRO = Duration.ofSeconds(30);

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();

    @DynamicPropertySource
    static void puntaAllaJwksDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
    }

    @AfterAll
    static void ferma() {
        GATEWAY.ferma();
    }

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

        /** I topic come li crea il Job kafka-topic: 3 partizioni. */
        @Bean
        NewTopic produzioneEventi() {
            return TopicBuilder.name("produzione.eventi").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic edizioniEventi() {
            return TopicBuilder.name(EDIZIONI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic pagamentiComandi() {
            return TopicBuilder.name(COMANDI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic pagamentiEsiti() {
            return TopicBuilder.name(ESITI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic deadLetter() {
            return TopicBuilder.name(DEAD_LETTER).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EdizioneRepository edizioni;

    @Autowired
    private ServizioPrenotazioni prenotazioni;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private KafkaTemplate<String, String> producer;

    @Autowired
    private KafkaListenerEndpointRegistry listener;

    /** Prima di ogni test i due listener devono avere le loro 3 partizioni, se no i messaggi aspettano. */
    @BeforeEach
    void consumerPronti() {
        await().atMost(ENTRO).until(() -> listener.getListenerContainers().stream()
                .allMatch(container -> container.getAssignedPartitions().size() == 3));
    }

    // ---------------------------------------------------------------- la storia di Giulia

    @Test
    void giuliaPagaConLaVisaDiProvaELeSueBottiglieSonoLa112ELa113() throws Exception {
        UUID estate = edizione();
        // 55 soci con 2 bottiglie e uno con 1, già pagate: 111 bottiglie numerate
        for (int socio = 1; socio <= 56; socio++) {
            UUID altra = prenotaConIlServizio(estate, socio <= 55 ? 2 : 1);
            prenotazioni.pagamentoRiuscito(altra, UUID.randomUUID(), "pi_prova_" + socio);
        }
        String giulia = persona("github");
        UUID sua = prenota(giulia, estate, 2);

        paga(giulia, sua, "pm_card_visa")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stato").value("IN_ATTESA_PAGAMENTO"))
                .andExpect(jsonPath("$.pagamento.metodo").value("pm_card_visa"))
                .andExpect(jsonPath("$.numeriBottiglia.length()").value(0));

        // Il comando per Pagamenti, dall'outbox: chiave la prenotazione, importo in centesimi
        ConsumerRecord<String, String> comando = leggi(COMANDI, messaggio(sua, "RichiestaPagamento")).getFirst();
        assertThat(comando.key()).isEqualTo(sua.toString());
        JsonNode richiesta = json.readTree(comando.value()).path("payload");
        assertThat(richiesta.path("edizioneId").asText()).isEqualTo(estate.toString());
        assertThat(richiesta.path("acquirente").asText()).isEqualTo(giulia);
        assertThat(richiesta.path("importoCentesimi").asLong()).isEqualTo(9000);
        assertThat(richiesta.path("valuta").asText()).isEqualTo("eur");
        assertThat(richiesta.path("metodo").asText()).isEqualTo("pm_card_visa");
        assertThat(richiesta.path("descrizione").asText()).startsWith("GinVault: 2 bottiglie, edizione lavanda del ");

        // Pagamenti risponde: Stripe ha addebitato
        UUID esito = UUID.randomUUID();
        UUID pagamento = UUID.randomUUID();
        manda(sua, pagamentoRiuscito(esito, sua, pagamento, estate, giulia, "pi_3QxYzGiulia"));

        await().atMost(ENTRO).until(() -> "CONFERMATA".equals(stato(sua)));
        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni/{id}", sua)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("CONFERMATA"))
                .andExpect(jsonPath("$.numeriBottiglia[0]").value(112))
                .andExpect(jsonPath("$.numeriBottiglia[1]").value(113))
                .andExpect(jsonPath("$.pagamento.riferimento").value("pi_3QxYzGiulia"))
                .andExpect(jsonPath("$.pagamento.confermataIl").isNotEmpty())
                .andExpect(jsonPath("$.annullamento").isEmpty());
        assertThat(esitoElaborato(esito)).isEqualTo("CONFERMATA");
        assertThat(numerate(estate)).isEqualTo(113);

        ConsumerRecord<String, String> confermata = leggi(EDIZIONI, messaggio(sua, "PrenotazioneConfermata"))
                .getFirst();
        assertThat(confermata.key()).isEqualTo(estate.toString());
        JsonNode evento = json.readTree(confermata.value()).path("payload");
        assertThat(evento.path("numeriBottiglia").toString()).isEqualTo("[112,113]");
        assertThat(evento.path("acquirente").asText()).isEqualTo(giulia);
        assertThat(evento.path("canale").asText()).isEqualTo("SOCI");
        assertThat(evento.path("totale").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(evento.path("pagamentoId").asText()).isEqualTo(pagamento.toString());
    }

    // ---------------------------------------------------------------- compensazione

    @Test
    void conLaCartaRifiutataLaPrenotazioneSiAnnullaELeBottiglieTornanoLibere() throws Exception {
        UUID estate = edizione();
        String giulia = persona("github");
        UUID sua = prenota(giulia, estate, 2);
        assertThat(prenotate(estate, Canale.SOCI)).isEqualTo(2);

        paga(giulia, sua, "pm_card_chargeDeclined").andExpect(status().isAccepted());
        JsonNode richiesta = json.readTree(leggi(COMANDI, messaggio(sua, "RichiestaPagamento")).getFirst().value());
        assertThat(richiesta.path("payload").path("metodo").asText()).isEqualTo("pm_card_chargeDeclined");

        UUID esito = UUID.randomUUID();
        manda(sua, pagamentoFallito(esito, sua, estate, giulia));

        await().atMost(ENTRO).until(() -> "ANNULLATA".equals(stato(sua)));
        assertThat(prenotate(estate, Canale.SOCI)).isZero();
        assertThat(esitoElaborato(esito)).isEqualTo("ANNULLATA");
        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni/{id}", sua)))
                .andExpect(jsonPath("$.annullamento.causa").value("PAGAMENTO_RIFIUTATO"))
                .andExpect(jsonPath("$.annullamento.spiegazione").value(RIFIUTATA))
                .andExpect(jsonPath("$.numeriBottiglia.length()").value(0));

        JsonNode evento = json.readTree(leggi(EDIZIONI, messaggio(sua, "PrenotazioneAnnullata")).getFirst().value())
                .path("payload");
        assertThat(evento.path("causa").asText()).isEqualTo("PAGAMENTO_RIFIUTATO");
        assertThat(evento.path("bottiglie").asInt()).isEqualTo(2);
        assertThat(evento.path("spiegazione").asText()).isEqualTo(RIFIUTATA);

        // Chiusa: non si paga di nuovo, ma Giulia può prenotare ancora le sue 2 bottiglie
        paga(giulia, sua, "pm_card_visa")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("PRENOTAZIONE_CHIUSA"));
        prenota(giulia, estate, 2);
        assertThat(prenotate(estate, Canale.SOCI)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- timeout e rimborso

    @Test
    void aTempoFinitoLaPrenotazioneSiAnnullaEIlPagamentoArrivatoDopoSiRestituisce(CapturedOutput log)
            throws Exception {
        UUID estate = edizione();
        String giulia = persona("github");
        UUID sua = prenota(giulia, estate, 2);
        paga(giulia, sua, "pm_card_visa").andExpect(status().isAccepted());
        leggi(COMANDI, messaggio(sua, "RichiestaPagamento"));

        // Pagamenti è fermo e il tempo per pagare finisce
        jdbc.update("update prenotazione set scade_il = now() - interval '1 minute' where id = ?", sua);
        await().atMost(ENTRO).until(() -> "ANNULLATA".equals(stato(sua)));
        assertThat(prenotate(estate, Canale.SOCI)).isZero();
        assertThat(log.getOut()).contains("Prenotazione " + sua + " scaduta: annullata");
        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni/{id}", sua)))
                .andExpect(jsonPath("$.annullamento.causa").value("SCADUTA"))
                .andExpect(jsonPath("$.annullamento.spiegazione").value(startsWith("Il tempo per pagare è finito il ")))
                .andExpect(jsonPath("$.rimborso").isEmpty());
        JsonNode annullata = json.readTree(leggi(EDIZIONI, messaggio(sua, "PrenotazioneAnnullata")).getFirst()
                .value()).path("payload");
        assertThat(annullata.path("causa").asText()).isEqualTo("SCADUTA");

        // Pagamenti riparte e Stripe addebita: le bottiglie non ci sono più, il pagamento si restituisce
        UUID riuscito = UUID.randomUUID();
        UUID pagamento = UUID.randomUUID();
        manda(sua, pagamentoRiuscito(riuscito, sua, pagamento, estate, giulia, "pi_tardi"));
        ConsumerRecord<String, String> rimborso = leggi(COMANDI, messaggio(sua, "RichiestaRimborso")).getFirst();
        assertThat(rimborso.key()).isEqualTo(sua.toString());
        assertThat(json.readTree(rimborso.value()).path("payload").path("motivo").asText())
                .isEqualTo("pagamento arrivato a prenotazione scaduta");
        assertThat(esitoElaborato(riuscito)).isEqualTo("RIMBORSO_RICHIESTO");
        assertThat(stato(sua)).isEqualTo("ANNULLATA");
        assertThat(numerate(estate)).isZero();
        assertThat(prenotate(estate, Canale.SOCI)).isZero();

        UUID eseguito = UUID.randomUUID();
        manda(sua, rimborsoEseguito(eseguito, sua, pagamento, estate, giulia, "re_tardi"));

        await().atMost(ENTRO).until(() -> jdbc.queryForObject(
                "select rimborsata_il is not null from prenotazione where id = ?", Boolean.class, sua));
        assertThat(esitoElaborato(eseguito)).isEqualTo("RIMBORSATA");
        mvc.perform(come(giulia, "SOCIO", get("/prenotazioni/{id}", sua)))
                .andExpect(jsonPath("$.stato").value("ANNULLATA"))
                .andExpect(jsonPath("$.rimborso.richiestoIl").isNotEmpty())
                .andExpect(jsonPath("$.rimborso.eseguitoIl").isNotEmpty())
                .andExpect(jsonPath("$.rimborso.riferimento").value("re_tardi"))
                .andExpect(jsonPath("$.pagamento.riferimento").value("pi_tardi"))
                .andExpect(jsonPath("$.numeriBottiglia.length()").value(0));
    }

    // ---------------------------------------------------------------- idempotenza

    @Test
    void unEsitoRipetutoNonCambiaNiente() throws Exception {
        UUID estate = edizione();
        String giulia = persona("github");
        UUID sua = prenota(giulia, estate, 2);
        paga(giulia, sua, "pm_card_visa").andExpect(status().isAccepted());
        UUID esito = UUID.randomUUID();
        UUID pagamento = UUID.randomUUID();
        String riuscito = pagamentoRiuscito(esito, sua, pagamento, estate, giulia, "pi_1");
        manda(sua, riuscito);
        await().atMost(ENTRO).until(() -> "CONFERMATA".equals(stato(sua)));

        // Lo stesso messaggio di nuovo (il publisher di Pagamenti non ha avuto la conferma di Kafka)
        attendiLetto(manda(sua, riuscito));
        // Lo stesso esito con un eventId nuovo (Pagamenti lo ripubblica per una seconda RichiestaPagamento)
        UUID ripubblicato = UUID.randomUUID();
        attendiLetto(manda(sua, pagamentoRiuscito(ripubblicato, sua, pagamento, estate, giulia, "pi_1")));

        assertThat(jdbc.queryForObject("select count(*) from evento_elaborato where event_id = ?", Integer.class,
                esito)).isEqualTo(1);
        assertThat(esitoElaborato(ripubblicato)).isEqualTo("GIA_REGISTRATO");
        assertThat(numeri(sua)).containsExactly(1, 2);
        assertThat(numerate(estate)).isEqualTo(2);
        assertThat(righeDiOutbox(sua, "PrenotazioneConfermata")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- errori

    @Test
    void ilPagamentoSiChiedeUnaVoltaConUnaCartaDelMenuESoloPerLeProprie() throws Exception {
        UUID estate = edizione();
        String giulia = persona("github");
        UUID sua = prenota(giulia, estate, 2);

        paga(giulia, sua, "pm_card_mastercard")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.metodiAccettati[0]").value("pm_card_visa"));
        paga(persona("google"), sua, "pm_card_visa").andExpect(status().isNotFound());
        paga(giulia, UUID.randomUUID(), "pm_card_visa").andExpect(status().isNotFound());
        assertThat(righeDiOutbox(sua, "RichiestaPagamento")).isZero();

        paga(giulia, sua, "pm_card_visa").andExpect(status().isAccepted());
        paga(giulia, sua, "pm_card_chargeDeclined")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Pagamento non possibile"))
                .andExpect(jsonPath("$.motivo").value("PAGAMENTO_GIA_RICHIESTO"));
        assertThat(righeDiOutbox(sua, "RichiestaPagamento")).isEqualTo(1);
    }

    @Test
    void unEsitoPerUnaPrenotazioneCheNonEsisteVaNelDeadLetterSenzaNuoviTentativi(CapturedOutput log)
            throws Exception {
        UUID sconosciuta = UUID.randomUUID();
        String esito = pagamentoRiuscito(UUID.randomUUID(), sconosciuta, UUID.randomUUID(), UUID.randomUUID(),
                persona("github"), "pi_x");
        RecordMetadata inviato = manda(sconosciuta, esito);

        ConsumerRecord<String, String> morto = leggi(DEAD_LETTER, letto -> letto.value().equals(esito)).getFirst();
        assertThat(morto.partition()).isEqualTo(inviato.partition());
        assertThat(testo(morto, "kafka_dlt-original-topic")).isEqualTo(ESITI);
        assertThat(testo(morto, "kafka_dlt-exception-fqcn") + " " + testo(morto, "kafka_dlt-exception-cause-fqcn"))
                .contains("PrenotazioneNonTrovata");
        String dove = ESITI + "-" + inviato.partition() + "@" + inviato.offset();
        assertThat(log.getOut()).contains(dove + " non elaborato (tentativo 1)")
                .doesNotContain(dove + " non elaborato (tentativo 2)");
    }

    // ---------------------------------------------------------------- concorrenza

    @Test
    void ventiConfermeInsiemePrendonoINumeriDa1A40SenzaBuchiNeDoppioni() throws Exception {
        UUID estate = edizione();
        List<UUID> sue = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sue.add(prenotaConIlServizio(estate, 2));
        }
        List<Callable<String>> conferme = new ArrayList<>();
        for (UUID prenotazione : sue) {
            conferme.add(() -> prenotazioni.pagamentoRiuscito(prenotazione, UUID.randomUUID(), "pi_insieme").name());
        }

        List<String> esiti = tuttiInsieme(conferme);

        assertThat(esiti).containsOnly("CONFERMATA").hasSize(20);
        List<Integer> tutti = jdbc.queryForList("select numero from bottiglia_numerata where edizione_id = ?"
                + " order by numero", Integer.class, estate);
        assertThat(tutti).isEqualTo(IntStream.rangeClosed(1, 40).boxed().toList());
        // Ogni prenotazione ha due numeri vicini: le conferme non si sono mescolate
        for (UUID prenotazione : sue) {
            List<Integer> suoi = numeri(prenotazione);
            assertThat(suoi).hasSize(2);
            assertThat(suoi.get(1)).isEqualTo(suoi.get(0) + 1);
        }
        assertThat(numerate(estate)).isEqualTo(40);
        assertThat(prenotate(estate, Canale.SOCI)).isEqualTo(40);
    }

    // ---------------------------------------------------------------- aiuti: dati e HTTP

    /** L'edizione d'estate: 600 bottiglie (12 kg a 20 g), SOCI 300, BAR 150, ONLINE 150, in prelazione. */
    private UUID edizione() {
        Map<Canale, Integer> quote = new EnumMap<>(Map.of(Canale.SOCI, 300, Canale.BAR, 150, Canale.ONLINE, 150));
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 2, "Lavanda",
                new BigDecimal("20"), null), 2, new BigDecimal("12"), ORIGINE, quote, Duration.ofDays(7));
        edizioni.save(edizione);
        return edizione.id();
    }

    /** Un socio nuovo prenota con il servizio, senza HTTP: per preparare i dati. */
    private UUID prenotaConIlServizio(UUID edizione, int bottiglie) {
        return prenotazioni.prenota(edizione, new Acquirente(persona("github"), true), bottiglie).id();
    }

    /** Un "sub" nuovo per ogni persona: i test non si mescolano le prenotazioni. */
    private static String persona(String provider) {
        return provider + ":" + UUID.randomUUID();
    }

    private UUID prenota(String socio, UUID edizione, int bottiglie) throws Exception {
        String risposta = mvc.perform(come(socio, "SOCIO", post("/edizioni/{id}/prenotazioni", edizione)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bottiglie\": " + bottiglie + "}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(risposta, "$.id"));
    }

    private ResultActions paga(String socio, UUID prenotazione, String metodo) throws Exception {
        return mvc.perform(come(socio, "SOCIO", post("/prenotazioni/{id}/pagamento", prenotazione)
                .contentType(MediaType.APPLICATION_JSON).content("{\"metodo\": \"" + metodo + "\"}")));
    }

    private static MockHttpServletRequestBuilder come(String sub, String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.tokenPer(sub, ruolo));
    }

    // ---------------------------------------------------------------- aiuti: gli esiti di Pagamenti

    private String pagamentoRiuscito(UUID eventId, UUID prenotazione, UUID pagamento, UUID edizione,
                                     String acquirente, String riferimento) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prenotazioneId", prenotazione);
        payload.put("pagamentoId", pagamento);
        payload.put("edizioneId", edizione);
        payload.put("acquirente", acquirente);
        payload.put("importoCentesimi", 9000);
        payload.put("valuta", "eur");
        payload.put("riferimentoStripe", riferimento);
        return busta(eventId, "PagamentoRiuscito", payload);
    }

    private String pagamentoFallito(UUID eventId, UUID prenotazione, UUID edizione, String acquirente)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prenotazioneId", prenotazione);
        payload.put("pagamentoId", UUID.randomUUID());
        payload.put("edizioneId", edizione);
        payload.put("acquirente", acquirente);
        payload.put("motivo", "CARTA_RIFIUTATA");
        payload.put("codice", "generic_decline");
        payload.put("spiegazione", RIFIUTATA);
        return busta(eventId, "PagamentoFallito", payload);
    }

    private String rimborsoEseguito(UUID eventId, UUID prenotazione, UUID pagamento, UUID edizione,
                                    String acquirente, String riferimento) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prenotazioneId", prenotazione);
        payload.put("pagamentoId", pagamento);
        payload.put("edizioneId", edizione);
        payload.put("acquirente", acquirente);
        payload.put("importoCentesimi", 9000);
        payload.put("valuta", "eur");
        payload.put("riferimentoStripe", riferimento);
        payload.put("motivo", "pagamento arrivato a prenotazione scaduta");
        return busta(eventId, "RimborsoEseguito", payload);
    }

    private String busta(UUID eventId, String tipo, Map<String, Object> payload) throws Exception {
        Map<String, Object> busta = new LinkedHashMap<>();
        busta.put("eventId", eventId);
        busta.put("tipo", tipo);
        busta.put("versione", 1);
        busta.put("occorsoIl", Instant.now().toString());
        busta.put("payload", payload);
        return json.writeValueAsString(busta);
    }

    /** Su pagamenti.esiti, con chiave l'id della prenotazione, come l'outbox di Pagamenti. */
    private RecordMetadata manda(UUID prenotazione, String valore) throws Exception {
        return producer.send(ESITI, prenotazione.toString(), valore).get().getRecordMetadata();
    }

    // ---------------------------------------------------------------- aiuti: lettura

    private String stato(UUID prenotazione) {
        return jdbc.queryForObject("select stato from prenotazione where id = ?", String.class, prenotazione);
    }

    private List<Integer> numeri(UUID prenotazione) {
        return jdbc.queryForList("select numero from bottiglia_numerata where prenotazione_id = ? order by numero",
                Integer.class, prenotazione);
    }

    private int numerate(UUID edizione) {
        return jdbc.queryForObject("select bottiglie_numerate from edizione where id = ?", Integer.class, edizione);
    }

    private int prenotate(UUID edizione, Canale canale) {
        return jdbc.queryForObject("select prenotate from quota where edizione_id = ? and canale = ?", Integer.class,
                edizione, canale.name());
    }

    private String esitoElaborato(UUID eventId) {
        return jdbc.queryForObject("select esito from evento_elaborato where event_id = ?", String.class, eventId);
    }

    /** Le righe di outbox di un tipo che parlano di questa prenotazione. */
    private int righeDiOutbox(UUID prenotazione, String tipo) {
        return jdbc.queryForObject("select count(*) from outbox where tipo = ? and messaggio like ?", Integer.class,
                tipo, "%\"prenotazioneId\":\"" + prenotazione + "\"%");
    }

    private static Predicate<ConsumerRecord<String, String>> messaggio(UUID prenotazione, String tipo) {
        return letto -> letto.value().contains("\"tipo\":\"" + tipo + "\"")
                && letto.value().contains("\"prenotazioneId\":\"" + prenotazione + "\"");
    }

    /** Aspetta che il consumer group "edizioni" abbia elaborato il messaggio: l'offset salvato lo supera. */
    private void attendiLetto(RecordMetadata messaggio) {
        TopicPartition partizione = new TopicPartition(messaggio.topic(), messaggio.partition());
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            await().atMost(ENTRO).until(() -> {
                Map<TopicPartition, OffsetAndMetadata> offset = admin.listConsumerGroupOffsets("edizioni")
                        .partitionsToOffsetAndMetadata().get();
                OffsetAndMetadata salvato = offset.get(partizione);
                return salvato != null && salvato.offset() > messaggio.offset();
            });
        }
    }

    /** Legge un topic dall'inizio, con un consumer group nuovo, finché trova un messaggio che va bene. */
    private List<ConsumerRecord<String, String>> leggi(String topic, Predicate<ConsumerRecord<String, String>> cerca) {
        Map<String, Object> configurazione = new HashMap<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        List<ConsumerRecord<String, String>> trovati = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configurazione)) {
            consumer.subscribe(List.of(topic));
            await().atMost(ENTRO).pollInSameThread().until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(letto -> {
                    if (cerca.test(letto)) {
                        trovati.add(letto);
                    }
                });
                return !trovati.isEmpty();
            });
        }
        return trovati;
    }

    private static String testo(ConsumerRecord<String, String> messaggio, String header) {
        Header valore = messaggio.headers().lastHeader(header);
        return valore == null ? null : new String(valore.value(), StandardCharsets.UTF_8);
    }

    /** Tutte le chiamate partono nello stesso istante, ognuna nel suo thread (e nella sua transazione). */
    private static List<String> tuttiInsieme(List<Callable<String>> chiamate) throws Exception {
        ExecutorService thread = Executors.newFixedThreadPool(chiamate.size());
        CountDownLatch via = new CountDownLatch(1);
        try {
            List<Future<String>> futuri = new ArrayList<>();
            for (Callable<String> chiamata : chiamate) {
                futuri.add(thread.submit(() -> {
                    via.await();
                    return chiamata.call();
                }));
            }
            via.countDown();
            List<String> esiti = new ArrayList<>();
            for (Future<String> futuro : futuri) {
                esiti.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return esiti;
        } finally {
            thread.shutdownNow();
        }
    }
}
