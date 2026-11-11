package it.ginvault.pagamenti;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Pagamenti dall'inizio alla fine, con PostgreSQL e Kafka VERI (Testcontainers)
 * e uno Stripe FINTO (WireMock). I test fanno la parte di Edizioni: scrivono
 * su pagamenti.comandi i comandi come li scriverà lei (la busta di
 * docs/eventi.md) e leggono gli esiti su pagamenti.esiti.
 *
 *  - la storia: Giulia paga 90,00 euro con pm_card_visa -> PagamentoRiuscito;
 *    con pm_card_chargeDeclined -> PagamentoFallito;
 *  - IDEMPOTENZA: lo stesso comando due volte non chiama Stripe due volte; un
 *    altro comando per la stessa prenotazione ripete l'esito senza Stripe;
 *  - RETRY: Stripe risponde 500 e poi 200: si paga al secondo tentativo, con
 *    la stessa chiave di idempotenza;
 *  - DEAD LETTER: Stripe sempre giù, comandi illeggibili o sconosciuti, un
 *    rimborso di un pagamento rifiutato;
 *  - RIMBORSO: RichiestaRimborso -> RimborsoEseguito.
 *
 * Ogni test usa una prenotazione nuova, e lo Stripe finto risponde in base
 * alla prenotazione (metadata[prenotazioneId]): i test non si disturbano.
 * Tentativi più rapidi che in produzione: 3, a 200, 400, 400 ms.
 */
@SpringBootTest(properties = {
        "pagamenti.outbox.intervallo-ms=200",
        "pagamenti.consumer.tentativi=3",
        "pagamenti.consumer.attesa-iniziale=200ms",
        "pagamenti.consumer.attesa-massima=400ms",
        "pagamenti.stripe.chiave=sk_test_finta_per_i_test",
        "pagamenti.stripe.attesa-risposta=2s"})
class PagamentiIntegrazioneTest {

    private static final String COMANDI = "pagamenti.comandi";
    private static final String ESITI = "pagamenti.esiti";
    private static final String DEAD_LETTER = "pagamenti.dlt";

    private static final Duration ENTRO = Duration.ofSeconds(30);

    private static final WireMockServer STRIPE = new WireMockServer(options().dynamicPort());

    static {
        STRIPE.start();
    }

    @DynamicPropertySource
    static void puntaAlloStripeFinto(DynamicPropertyRegistry registro) {
        registro.add("pagamenti.stripe.indirizzo", STRIPE::baseUrl);
    }

    @AfterAll
    static void ferma() {
        STRIPE.stop();
    }

    /** Come negli altri servizi: i container sono bean, li ferma Spring dopo i consumer e il publisher. */
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

        /** I topic come li crea il servizio kafka-topic: 3 partizioni. */
        @Bean
        NewTopic comandi() {
            return TopicBuilder.name(COMANDI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic esiti() {
            return TopicBuilder.name(ESITI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic deadLetter() {
            return TopicBuilder.name(DEAD_LETTER).partitions(3).replicas(1).build();
        }
    }

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

    /** Prima di ogni test il consumer deve avere le sue 3 partizioni, se no i comandi aspettano. */
    @BeforeEach
    void consumerPronto() {
        await().atMost(ENTRO).until(() -> listener.getListenerContainers().stream()
                .allMatch(container -> container.getAssignedPartitions().size() == 3));
    }

    // ---------------------------------------------------------------- la storia di Giulia

    @Test
    void conUnaCartaBuonaLaPrenotazioneEPagataEArrivaPagamentoRiuscito() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        UUID edizione = UUID.randomUUID();
        stripeAddebita(prenotazione, paymentIntent("pi_giulia_" + corto(prenotazione), "succeeded"));

        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, edizione, "pm_card_visa"));

        ConsumerRecord<String, String> messaggio = leggi(ESITI, perPrenotazione(prenotazione), 1).getFirst();
        assertThat(messaggio.key()).isEqualTo(prenotazione.toString());
        JsonNode busta = json.readTree(messaggio.value());
        assertThat(busta.path("tipo").asText()).isEqualTo("PagamentoRiuscito");
        assertThat(busta.path("versione").asInt()).isEqualTo(1);
        assertThat(busta.path("eventId").asText()).isNotBlank();
        assertThat(busta.path("occorsoIl").asText()).isNotBlank();
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("prenotazioneId").asText()).isEqualTo(prenotazione.toString());
        assertThat(payload.path("edizioneId").asText()).isEqualTo(edizione.toString());
        assertThat(payload.path("acquirente").asText()).isEqualTo("github:1234567");
        assertThat(payload.path("importoCentesimi").asLong()).isEqualTo(9000);
        assertThat(payload.path("valuta").asText()).isEqualTo("eur");
        assertThat(payload.path("riferimentoStripe").asText()).isEqualTo("pi_giulia_" + corto(prenotazione));
        assertThat(payload.path("pagamentoId").asText()).isNotBlank();

        Map<String, Object> pagamento = jdbc.queryForMap("select stato, riferimento_stripe, metodo, importo_centesimi"
                + " from pagamento where prenotazione_id = ?", prenotazione);
        assertThat(pagamento).containsEntry("stato", "RIUSCITO")
                .containsEntry("metodo", "pm_card_visa")
                .containsEntry("importo_centesimi", 9000L);
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
    }

    @Test
    void conUnaCartaRifiutataArrivaPagamentoFallitoConIlMotivo() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error": {"type": "card_error", "code": "card_declined", "decline_code": "generic_decline",
                         "message": "Your card was declined.",
                         "payment_intent": {"id": "pi_rifiutato", "object": "payment_intent",
                                            "status": "requires_payment_method"}}}"""));

        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(),
                "pm_card_chargeDeclined"));

        JsonNode busta = json.readTree(leggi(ESITI, perPrenotazione(prenotazione), 1).getFirst().value());
        assertThat(busta.path("tipo").asText()).isEqualTo("PagamentoFallito");
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("motivo").asText()).isEqualTo("CARTA_RIFIUTATA");
        assertThat(payload.path("codice").asText()).isEqualTo("generic_decline");
        assertThat(payload.path("spiegazione").asText()).isEqualTo("La banca ha rifiutato la carta.");
        assertThat(jdbc.queryForObject("select stato from pagamento where prenotazione_id = ?", String.class,
                prenotazione)).isEqualTo("RIFIUTATO");
    }

    // ---------------------------------------------------------------- idempotenza

    @Test
    void loStessoComandoDueVolteNonChiamaStripeDueVolte() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, paymentIntent("pi_una_volta", "succeeded"));
        String comando = richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa");

        manda(prenotazione, comando);
        leggi(ESITI, perPrenotazione(prenotazione), 1);
        // Lo stesso messaggio di nuovo: l'outbox di Edizioni non ha avuto la conferma e lo rimanda
        attendiLetto(manda(prenotazione, comando));

        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
        assertThat(righeDiOutbox(prenotazione)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pagamento where prenotazione_id = ?", Integer.class,
                prenotazione)).isEqualTo(1);
    }

    @Test
    void unAltroComandoPerLaStessaPrenotazioneRipeteLEsitoSenzaStripe() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        UUID edizione = UUID.randomUUID();
        stripeAddebita(prenotazione, paymentIntent("pi_primo", "succeeded"));

        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, edizione, "pm_card_visa"));
        leggi(ESITI, perPrenotazione(prenotazione), 1);
        // Un comando nuovo (altro eventId) per la stessa prenotazione, anche con un'altra carta
        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, edizione, "pm_card_chargeDeclined"));

        List<ConsumerRecord<String, String>> esiti = leggi(ESITI, perPrenotazione(prenotazione), 2);
        for (ConsumerRecord<String, String> esito : esiti) {
            JsonNode busta = json.readTree(esito.value());
            assertThat(busta.path("tipo").asText()).isEqualTo("PagamentoRiuscito");
            assertThat(busta.path("payload").path("riferimentoStripe").asText()).isEqualTo("pi_primo");
        }
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
        assertThat(jdbc.queryForObject("select count(*) from pagamento where prenotazione_id = ?", Integer.class,
                prenotazione)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- retry e dead letter

    @Test
    void seStripeRisponde500SiPagaAlTentativoDopoConLaStessaChiave() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        String scenario = "stripe-guasto-" + prenotazione;
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .withFormParam("metadata[prenotazioneId]", equalTo(prenotazione.toString()))
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(erroreDiStripe(500))
                .willSetStateTo("ripartito"));
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .withFormParam("metadata[prenotazioneId]", equalTo(prenotazione.toString()))
                .inScenario(scenario).whenScenarioStateIs("ripartito")
                .willReturn(paymentIntent("pi_al_secondo", "succeeded")));

        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa"));

        JsonNode busta = json.readTree(leggi(ESITI, perPrenotazione(prenotazione), 1).getFirst().value());
        assertThat(busta.path("tipo").asText()).isEqualTo("PagamentoRiuscito");
        assertThat(busta.path("payload").path("riferimentoStripe").asText()).isEqualTo("pi_al_secondo");
        STRIPE.verify(2, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
    }

    @Test
    void conStripeSempreGiuFinitiITentativiIlComandoVaNelDeadLetter() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, erroreDiStripe(503));
        String comando = richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa");

        RecordMetadata inviato = manda(prenotazione, comando);

        ConsumerRecord<String, String> morto = leggi(DEAD_LETTER, letto -> letto.value().equals(comando), 1)
                .getFirst();
        assertThat(morto.partition()).isEqualTo(inviato.partition());
        assertThat(testo(morto, "kafka_dlt-original-topic")).isEqualTo(COMANDI);
        assertThat(eccezione(morto)).contains("ProviderNonDisponibile");
        assertThat(testo(morto, "kafka_dlt-exception-message")).contains("ApiException 503");
        // Il primo tentativo e 3 nuovi, sempre con la stessa chiave; nessun pagamento salvato
        STRIPE.verify(4, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
        assertThat(jdbc.queryForObject("select count(*) from pagamento where prenotazione_id = ?", Integer.class,
                prenotazione)).isZero();
    }

    @Test
    void unComandoIllegibileOSconosciutoVaSubitoNelDeadLetterSenzaFermareGliAltri() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, paymentIntent("pi_dopo_i_guasti", "succeeded"));
        String nonJson = "non è json " + UUID.randomUUID();
        String sconosciuto = busta(UUID.randomUUID(), "AnnullaTutto", Map.of("prenotazioneId", prenotazione));
        String senzaImporto = richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa")
                .replace("\"importoCentesimi\":9000,", "");

        // Stessa chiave: stessa partizione, e dopo i tre sbagliati un comando buono
        manda(prenotazione, nonJson);
        manda(prenotazione, sconosciuto);
        manda(prenotazione, senzaImporto);
        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa"));

        ConsumerRecord<String, String> primo = leggi(DEAD_LETTER, letto -> letto.value().equals(nonJson), 1)
                .getFirst();
        assertThat(eccezione(primo)).contains("ComandoNonValido");
        assertThat(testo(primo, "kafka_dlt-exception-message")).contains("non è JSON");
        assertThat(testo(leggi(DEAD_LETTER, letto -> letto.value().equals(sconosciuto), 1).getFirst(),
                "kafka_dlt-exception-message")).contains("AnnullaTutto sconosciuto");
        assertThat(testo(leggi(DEAD_LETTER, letto -> letto.value().equals(senzaImporto), 1).getFirst(),
                "kafka_dlt-exception-message")).contains("senza importoCentesimi");

        JsonNode esito = json.readTree(leggi(ESITI, perPrenotazione(prenotazione), 1).getFirst().value());
        assertThat(esito.path("tipo").asText()).isEqualTo("PagamentoRiuscito");
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + prenotazione)));
    }

    // ---------------------------------------------------------------- rimborso

    @Test
    void ilRimborsoRestituisceIlPagamentoEArrivaRimborsoEseguito() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, paymentIntent("pi_da_rimborsare", "succeeded"));
        STRIPE.stubFor(post(urlEqualTo("/v1/refunds"))
                .withFormParam("metadata[prenotazioneId]", equalTo(prenotazione.toString()))
                .willReturn(okJson("""
                        {"id": "re_giulia", "object": "refund", "status": "succeeded", "amount": 9000,
                         "currency": "eur", "payment_intent": "pi_da_rimborsare"}""")));
        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(), "pm_card_visa"));
        leggi(ESITI, perPrenotazione(prenotazione), 1);

        manda(prenotazione, richiestaRimborso(UUID.randomUUID(), prenotazione));

        ConsumerRecord<String, String> rimborso = leggi(ESITI, perPrenotazione(prenotazione)
                .and(letto -> letto.value().contains("\"RimborsoEseguito\"")), 1).getFirst();
        assertThat(rimborso.key()).isEqualTo(prenotazione.toString());
        JsonNode payload = json.readTree(rimborso.value()).path("payload");
        assertThat(payload.path("riferimentoStripe").asText()).isEqualTo("re_giulia");
        assertThat(payload.path("importoCentesimi").asLong()).isEqualTo(9000);
        assertThat(payload.path("motivo").asText()).isEqualTo("pagamento arrivato a prenotazione scaduta");
        assertThat(jdbc.queryForObject("select stato from pagamento where prenotazione_id = ?", String.class,
                prenotazione)).isEqualTo("RIMBORSATO");
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("ginvault-rimborso-" + prenotazione))
                .withFormParam("payment_intent", equalTo("pi_da_rimborsare")));
    }

    @Test
    void ilRimborsoDiUnPagamentoRifiutatoVaNelDeadLetterSenzaChiamareStripe() throws Exception {
        UUID prenotazione = UUID.randomUUID();
        stripeAddebita(prenotazione, aResponse().withStatus(402).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error": {"type": "card_error", "code": "card_declined",
                         "decline_code": "generic_decline", "message": "Your card was declined."}}"""));
        manda(prenotazione, richiestaPagamento(UUID.randomUUID(), prenotazione, UUID.randomUUID(),
                "pm_card_chargeDeclined"));
        leggi(ESITI, perPrenotazione(prenotazione), 1);

        String rimborso = richiestaRimborso(UUID.randomUUID(), prenotazione);
        manda(prenotazione, rimborso);

        ConsumerRecord<String, String> morto = leggi(DEAD_LETTER, letto -> letto.value().equals(rimborso), 1)
                .getFirst();
        assertThat(eccezione(morto)).contains("RimborsoNonPossibile");
        STRIPE.verify(0, postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("ginvault-rimborso-" + prenotazione)));
    }

    // ---------------------------------------------------------------- aiuti: Stripe finto

    private static void stripeAddebita(UUID prenotazione, ResponseDefinitionBuilder risposta) {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .withFormParam("metadata[prenotazioneId]", equalTo(prenotazione.toString()))
                .willReturn(risposta));
    }

    private static ResponseDefinitionBuilder paymentIntent(String id, String stato) {
        return okJson("""
                {"id": "%s", "object": "payment_intent", "status": "%s", "amount": 9000, "currency": "eur"}"""
                .formatted(id, stato));
    }

    private static ResponseDefinitionBuilder erroreDiStripe(int stato) {
        return aResponse().withStatus(stato).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error": {"type": "api_error", "message": "Something went wrong on Stripe's end."}}""");
    }

    private static String corto(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- aiuti: comandi

    /** RichiestaPagamento v1, come la scriverà l'outbox di Edizioni: 2 bottiglie a 45,00 euro. */
    private String richiestaPagamento(UUID eventId, UUID prenotazione, UUID edizione, String metodo)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prenotazioneId", prenotazione);
        payload.put("edizioneId", edizione);
        payload.put("acquirente", "github:1234567");
        payload.put("importoCentesimi", 9000);
        payload.put("valuta", "eur");
        payload.put("metodo", metodo);
        payload.put("descrizione", "GinVault: 2 bottiglie dell'edizione d'estate");
        return busta(eventId, "RichiestaPagamento", payload);
    }

    private String richiestaRimborso(UUID eventId, UUID prenotazione) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prenotazioneId", prenotazione);
        payload.put("motivo", "pagamento arrivato a prenotazione scaduta");
        return busta(eventId, "RichiestaRimborso", payload);
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

    /** Chiave = id della prenotazione, come farà Edizioni. */
    private RecordMetadata manda(UUID prenotazione, String valore) throws Exception {
        return producer.send(COMANDI, prenotazione.toString(), valore).get().getRecordMetadata();
    }

    // ---------------------------------------------------------------- aiuti: lettura

    private static Predicate<ConsumerRecord<String, String>> perPrenotazione(UUID prenotazione) {
        return letto -> prenotazione.toString().equals(letto.key());
    }

    private int righeDiOutbox(UUID prenotazione) {
        return jdbc.queryForObject("select count(*) from outbox where chiave = ?", Integer.class,
                prenotazione.toString());
    }

    /**
     * Aspetta che il consumer group "pagamenti" abbia elaborato il messaggio:
     * l'offset salvato del gruppo va oltre quello del messaggio.
     */
    private void attendiLetto(RecordMetadata messaggio) {
        TopicPartition partizione = new TopicPartition(messaggio.topic(), messaggio.partition());
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            await().atMost(ENTRO).until(() -> {
                Map<TopicPartition, OffsetAndMetadata> offset = admin.listConsumerGroupOffsets("pagamenti")
                        .partitionsToOffsetAndMetadata().get();
                OffsetAndMetadata salvato = offset.get(partizione);
                return salvato != null && salvato.offset() > messaggio.offset();
            });
        }
    }

    /** Legge un topic dall'inizio, con un consumer group nuovo, finché trova almeno "quanti" messaggi giusti. */
    private List<ConsumerRecord<String, String>> leggi(String topic, Predicate<ConsumerRecord<String, String>> cerca,
                                                       int quanti) {
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
                return trovati.size() >= quanti;
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
