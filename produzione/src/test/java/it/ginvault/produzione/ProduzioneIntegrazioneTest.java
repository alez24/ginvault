package it.ginvault.produzione;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.produzione.sicurezza.GatewayDiProva;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Produzione dall'inizio alla fine, con PostgreSQL e Kafka VERI (Testcontainers).
 * I test fanno la parte di Edizioni e Magazzino: scrivono sui loro topic i
 * messaggi come li scrivono loro (la busta di docs/eventi.md).
 *
 *  - la storia: EdizioneCreata -> piano da 12 kg; ConsegnaRegistrata da 10,8
 *    -> piano a 10,8 (540 bottiglie) e ResaRicalcolata su produzione.eventi;
 *  - IDEMPOTENZA: lo stesso evento rimandato non ricalcola due volte;
 *  - RETRY: una consegna arrivata prima della sua edizione passa al nuovo tentativo;
 *  - DEAD LETTER: un messaggio illeggibile ci va subito, una consegna per
 *    un'edizione che non esiste dopo i tentativi.
 *
 * Tentativi più rapidi che in produzione: 4, a 200, 400, 800, 800 ms.
 */
@SpringBootTest(properties = {
        "produzione.outbox.intervallo-ms=200",
        "produzione.consumer.tentativi=4",
        "produzione.consumer.attesa-iniziale=200ms",
        "produzione.consumer.attesa-massima=800ms"})
@AutoConfigureMockMvc
class ProduzioneIntegrazioneTest {

    private static final String EDIZIONI = "edizioni.eventi";
    private static final String MAGAZZINO = "magazzino.eventi";
    private static final String PRODUZIONE = "produzione.eventi";
    private static final String DEAD_LETTER = "produzione.dlt";

    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final Duration ENTRO = Duration.ofSeconds(30);

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final String JWT_DI_MARCO = GATEWAY.token("DISTILLATORE");

    @DynamicPropertySource
    static void puntaAllaJwksDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
    }

    @AfterAll
    static void ferma() {
        GATEWAY.ferma();
    }

    /** Come in Magazzino: i container sono bean, li ferma Spring dopo i consumer e il publisher. */
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
        NewTopic edizioniEventi() {
            return TopicBuilder.name(EDIZIONI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic magazzinoEventi() {
            return TopicBuilder.name(MAGAZZINO).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic produzioneEventi() {
            return TopicBuilder.name(PRODUZIONE).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic deadLetter() {
            return TopicBuilder.name(DEAD_LETTER).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private MockMvc mvc;

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

    /** Prima di ogni test i consumer devono avere le loro 3 partizioni, se no i messaggi aspettano. */
    @BeforeEach
    void consumerPronti() {
        await().atMost(ENTRO).until(() -> listener.getListenerContainers().stream()
                .allMatch(container -> container.getAssignedPartitions().size() == 3));
    }

    // ---------------------------------------------------------------- la storia di Elena

    @Test
    void laConsegnaRidottaRicalcolaLaResaEArrivaSuProduzioneEventi() throws Exception {
        UUID edizione = UUID.randomUUID();
        manda(EDIZIONI, edizione.toString(), edizioneCreata(UUID.randomUUID(), edizione));

        await().atMost(ENTRO).untilAsserted(() -> mvc.perform(get("/piani").param("edizione", edizione.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].botanica").value("lavanda"))
                .andExpect(jsonPath("$[0].origineRichiesta").value(ORIGINE))
                .andExpect(jsonPath("$[0].kgDisponibili").value(12.0))
                .andExpect(jsonPath("$[0].bottiglieProducibili").value(600)));

        UUID ordine = UUID.randomUUID();
        manda(MAGAZZINO, "lavanda", consegnaRegistrata(UUID.randomUUID(), edizione, ordine, ORIGINE, "10.8"));

        await().atMost(ENTRO).untilAsserted(() -> mvc.perform(get("/piani").param("edizione", edizione.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_MARCO))
                .andExpect(jsonPath("$[0].kgDisponibili").value(10.8))
                .andExpect(jsonPath("$[0].bottiglieProducibili").value(540))
                .andExpect(jsonPath("$[0].consegne[0].ordineId").value(ordine.toString()))
                .andExpect(jsonPath("$[0].consegne[0].motivo").value("consegna ridotta: 10,8 su 12 kg")));

        ConsumerRecord<String, String> messaggio = leggi(PRODUZIONE,
                letto -> letto.value().contains(edizione.toString())).getFirst();
        assertThat(messaggio.key()).isEqualTo(edizione.toString());
        JsonNode busta = json.readTree(messaggio.value());
        assertThat(busta.path("tipo").asText()).isEqualTo("ResaRicalcolata");
        assertThat(busta.path("versione").asInt()).isEqualTo(1);
        assertThat(busta.path("eventId").asText()).isNotBlank();
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("edizioneId").asText()).isEqualTo(edizione.toString());
        assertThat(payload.path("kgDisponibiliPrima").decimalValue()).isEqualByComparingTo("12");
        assertThat(payload.path("kgDisponibili").decimalValue()).isEqualByComparingTo("10.8");
        assertThat(payload.path("ordineId").asText()).isEqualTo(ordine.toString());
        assertThat(payload.path("motivo").asText()).isEqualTo("consegna ridotta: 10,8 su 12 kg");
        assertThat(righeDiOutbox(edizione)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- idempotenza

    @Test
    void loStessoEventoRimandatoNonRicalcolaDueVolte() throws Exception {
        UUID edizione = pianoDaDodiciKg();
        UUID eventId = UUID.randomUUID();
        String consegna = consegnaRegistrata(eventId, edizione, UUID.randomUUID(), ORIGINE, "10.8");
        manda(MAGAZZINO, "lavanda", consegna);
        await().atMost(ENTRO).until(() -> kgDisponibili(edizione).compareTo(new BigDecimal("10.8")) == 0);

        // Lo stesso messaggio di nuovo (il publisher dell'outbox non ha avuto la conferma) e
        // un messaggio con lo stesso eventId ma un altro ordine: tutti e due scartati
        RecordMetadata doppione = manda(MAGAZZINO, "lavanda", consegna);
        RecordMetadata stessoEventId = manda(MAGAZZINO, "lavanda",
                consegnaRegistrata(eventId, edizione, UUID.randomUUID(), ORIGINE, "6"));
        attendiLetto(doppione);
        attendiLetto(stessoEventId);

        assertThat(kgDisponibili(edizione)).isEqualByComparingTo("10.8");
        assertThat(jdbc.queryForObject("select count(*) from piano_consegna c join piano_di_produzione p"
                + " on p.id = c.piano_id where p.edizione_id = ?", Integer.class, edizione)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from evento_elaborato where event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
        assertThat(righeDiOutbox(edizione)).isEqualTo(1);
    }

    @Test
    void lEdizioneCreataDueVolteCreaUnSoloPiano() throws Exception {
        UUID edizione = UUID.randomUUID();
        manda(EDIZIONI, edizione.toString(), edizioneCreata(UUID.randomUUID(), edizione));
        RecordMetadata secondo = manda(EDIZIONI, edizione.toString(), edizioneCreata(UUID.randomUUID(), edizione));
        attendiLetto(secondo);

        assertThat(jdbc.queryForObject("select count(*) from piano_di_produzione where edizione_id = ?",
                Integer.class, edizione)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- retry e dead letter

    @Test
    void seLaConsegnaArrivaPrimaDellEdizioneBastaRiprovare() throws Exception {
        UUID edizione = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        manda(MAGAZZINO, "lavanda", consegnaRegistrata(eventId, edizione, UUID.randomUUID(), ORIGINE, "10.8"));
        Thread.sleep(300);   // il primo tentativo non trova il piano
        manda(EDIZIONI, edizione.toString(), edizioneCreata(UUID.randomUUID(), edizione));

        await().atMost(ENTRO).until(() -> kgDisponibili(edizione) != null
                && kgDisponibili(edizione).compareTo(new BigDecimal("10.8")) == 0);
        assertThat(jdbc.queryForObject("select count(*) from evento_elaborato where event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void unMessaggioIllegibileVaSubitoNelDeadLetterSenzaFermareGliAltri() throws Exception {
        UUID edizione = pianoDaDodiciKg();
        String nonJson = "non è json " + UUID.randomUUID();
        String versioneDue = consegnaRegistrata(UUID.randomUUID(), edizione, UUID.randomUUID(), ORIGINE, "10.8")
                .replace("\"versione\":1", "\"versione\":2");
        manda(MAGAZZINO, "lavanda", nonJson);
        manda(MAGAZZINO, "lavanda", versioneDue);
        // Dopo i due messaggi sbagliati, nella stessa partizione, una consegna buona
        manda(MAGAZZINO, "lavanda", consegnaRegistrata(UUID.randomUUID(), edizione, UUID.randomUUID(), ORIGINE, "11"));

        ConsumerRecord<String, String> primo = leggi(DEAD_LETTER, letto -> letto.value().equals(nonJson)).getFirst();
        assertThat(testo(primo, "kafka_dlt-original-topic")).isEqualTo(MAGAZZINO);
        assertThat(eccezione(primo)).contains("EventoNonValido");
        assertThat(testo(primo, "kafka_dlt-exception-message")).contains("non è JSON");

        ConsumerRecord<String, String> secondo = leggi(DEAD_LETTER,
                letto -> letto.value().equals(versioneDue)).getFirst();
        assertThat(testo(secondo, "kafka_dlt-exception-message")).contains("versione 2 non supportata");

        await().atMost(ENTRO).until(() -> kgDisponibili(edizione).compareTo(new BigDecimal("11")) == 0);
    }

    @Test
    void unaConsegnaPerUnEdizioneCheNonEsisteFiniscoITentativiEVaNelDeadLetter() throws Exception {
        UUID sconosciuta = UUID.randomUUID();
        String consegna = consegnaRegistrata(UUID.randomUUID(), sconosciuta, UUID.randomUUID(), ORIGINE, "10.8");
        RecordMetadata inviato = manda(MAGAZZINO, "lavanda", consegna);

        ConsumerRecord<String, String> morto = leggi(DEAD_LETTER, letto -> letto.value().equals(consegna)).getFirst();
        assertThat(morto.partition()).isEqualTo(inviato.partition());
        assertThat(testo(morto, "kafka_dlt-original-topic")).isEqualTo(MAGAZZINO);
        assertThat(testo(morto, "kafka_dlt-original-partition")).isNotNull();
        assertThat(eccezione(morto)).contains("PianoNonTrovato");
        assertThat(testo(morto, "kafka_dlt-exception-message")).contains(sconosciuta.toString());
    }

    // ---------------------------------------------------------------- aiuti: messaggi

    /** Un piano per una nuova edizione d'estate (12 kg di lavanda, 20 g, 600 bottiglie), già creato. */
    private UUID pianoDaDodiciKg() throws Exception {
        UUID edizione = UUID.randomUUID();
        manda(EDIZIONI, edizione.toString(), edizioneCreata(UUID.randomUUID(), edizione));
        await().atMost(ENTRO).until(() -> kgDisponibili(edizione) != null);
        return edizione;
    }

    /** EdizioneCreata v1, come la scrive l'outbox di Edizioni. */
    private String edizioneCreata(UUID eventId, UUID edizione) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("edizioneId", edizione);
        payload.put("formulazione", Map.of("id", UUID.randomUUID(), "versione", 2));
        payload.put("botanicaDiStagione", "lavanda");
        payload.put("grammiPerBottiglia", new BigDecimal("20.00"));
        payload.put("origineBotanica", ORIGINE);
        payload.put("kgPrevisti", new BigDecimal("12.000"));
        payload.put("tiratura", 600);
        payload.put("quote", Map.of("SOCI", 200, "BAR", 200, "ONLINE", 200));
        return busta(eventId, "EdizioneCreata", payload);
    }

    /** ConsegnaRegistrata v1, come la scrive l'outbox di Magazzino: 12 kg ordinati. */
    private String consegnaRegistrata(UUID eventId, UUID edizione, UUID ordine, String origine, String kgArrivati)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ordineId", ordine);
        payload.put("edizioneId", edizione);
        payload.put("lottoId", UUID.randomUUID());
        payload.put("fornitoreId", UUID.fromString("f0000000-0000-4000-8000-000000000001"));
        payload.put("botanica", "lavanda");
        payload.put("origine", origine);
        payload.put("kgOrdinati", new BigDecimal("12.000"));
        payload.put("kgArrivati", new BigDecimal(kgArrivati));
        payload.put("esitoQualita", "CONFORME");
        payload.put("consegnataIl", "2026-08-06");
        return busta(eventId, "ConsegnaRegistrata", payload);
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

    private RecordMetadata manda(String topic, String chiave, String valore) throws Exception {
        return producer.send(topic, chiave, valore).get().getRecordMetadata();
    }

    // ---------------------------------------------------------------- aiuti: lettura

    private BigDecimal kgDisponibili(UUID edizione) {
        List<BigDecimal> kg = jdbc.queryForList("select kg_disponibili from piano_di_produzione where edizione_id = ?",
                BigDecimal.class, edizione);
        return kg.isEmpty() ? null : kg.getFirst();
    }

    private int righeDiOutbox(UUID edizione) {
        return jdbc.queryForObject("select count(*) from outbox where tipo = 'ResaRicalcolata' and chiave = ?",
                Integer.class, edizione.toString());
    }

    /**
     * Aspetta che il consumer group "produzione" abbia elaborato il messaggio:
     * l'offset salvato del gruppo va oltre quello del messaggio.
     */
    private void attendiLetto(RecordMetadata messaggio) {
        TopicPartition partizione = new TopicPartition(messaggio.topic(), messaggio.partition());
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            await().atMost(ENTRO).until(() -> {
                Map<TopicPartition, OffsetAndMetadata> offset = admin.listConsumerGroupOffsets("produzione")
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

    /** La classe dell'eccezione: nell'header dell'eccezione o in quello della causa. */
    private static String eccezione(ConsumerRecord<String, String> messaggio) {
        return testo(messaggio, "kafka_dlt-exception-fqcn") + " " + testo(messaggio, "kafka_dlt-exception-cause-fqcn");
    }
}
