package it.ginvault.magazzino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.dominio.DatiConsegna;
import it.ginvault.magazzino.dominio.EsitoQualita;
import it.ginvault.magazzino.infrastruttura.datiiniziali.DatiIniziali;
import it.ginvault.magazzino.sicurezza.GatewayDiProva;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Magazzino dall'inizio alla fine, con PostgreSQL e Kafka VERI (Testcontainers):
 *  - la consegna ridotta di Elena: lotto, storico del fornitore, riga di outbox
 *    nella stessa transazione, evento su magazzino.eventi con chiave "lavanda";
 *  - le consegne rifiutate (seconda consegna, più kg di quelli ordinati) non
 *    lasciano né lotto né evento;
 *  - se la riga di outbox non si può scrivere, salta anche la consegna;
 *  - TRANSACTIONAL OUTBOX: con Kafka in pausa la consegna si registra lo stesso
 *    (201), l'evento aspetta nella tabella e parte quando Kafka torna.
 *
 * Il fuso e la data sono fissati: "oggi" è il 6 agosto 2026, giorno della consegna.
 */
@SpringBootTest(properties = "magazzino.outbox.intervallo-ms=200")
@AutoConfigureMockMvc
class MagazzinoIntegrazioneTest {

    private static final String TOPIC = "magazzino.eventi";
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final String JWT_DI_ELENA = GATEWAY.token("RESP_ACQUISTI");

    @DynamicPropertySource
    static void puntaAllaJwksDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
    }

    @AfterAll
    static void ferma() {
        GATEWAY.ferma();
    }

    /**
     * I container sono bean: li avvia e li ferma Spring, insieme al contesto.
     * Alla chiusura Spring ferma prima il publisher e il producer Kafka, poi i
     * container (con @Container si fermerebbero a fine classe, mentre il
     * publisher, ancora vivo nel contesto in cache, continua a girare).
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigurazioneDiProva {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }

        /** Lo stesso Kafka 4.3 di docker-compose.yml, in modalità KRaft. */
        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.3.0");
        }

        /** Il topic come lo crea il Job kafka-topic: 3 partizioni. */
        @Bean
        NewTopic magazzinoEventi() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        @Primary
        Clock orologioFisso() {
            return Clock.fixed(ADESSO, ZoneId.of("Europe/Rome"));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ServizioApprovvigionamento servizio;

    @Autowired
    private DatiIniziali datiIniziali;

    @Autowired
    private KafkaContainer kafka;

    // ---------------------------------------------------------------- la storia di Elena

    @Test
    void laConsegnaRidottaCreaIlLottoAggiornaLoStoricoEArrivaSuKafka() throws Exception {
        UUID edizione = UUID.randomUUID();
        UUID ordine = creaOrdine("lavanda", "12", edizione);

        registraConsegna(ordine, "10.8", "2026-08-06")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/lotti/")))
                .andExpect(jsonPath("$.ordine.stato").value("CONSEGNATO"))
                .andExpect(jsonPath("$.ordine.consegna.ridotta").value(true))
                .andExpect(jsonPath("$.ordine.consegna.kgMancanti").value(1.2))
                .andExpect(jsonPath("$.ordine.consegna.inRitardo").value(true))
                .andExpect(jsonPath("$.ordine.consegna.giorniDiRitardo").value(6))
                .andExpect(jsonPath("$.lotto.kg").value(10.8))
                .andExpect(jsonPath("$.lotto.origine").value("Alta Val Varaita (Alpi Cozie)"))
                .andExpect(jsonPath("$.lotto.utilizzabile").value(true));

        // Il lotto è in magazzino, insieme a quello dei campi (dati iniziali)
        mvc.perform(come(MockMvcRequestBuilders.get("/lotti").param("botanica", "Lavanda")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ordineId").value(hasItem(ordine.toString())))
                .andExpect(jsonPath("$[*].id").value(hasItem(DatiIniziali.LOTTO_LAVANDA_DEI_CAMPI.toString())));

        // Lo storico del fornitore: la consegna più recente per prima, con la nota del ritardo
        mvc.perform(come(MockMvcRequestBuilders.get("/fornitori/{id}", DatiIniziali.RACCOGLITORI_VAL_VARAITA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storico[0].ordineId").value(ordine.toString()))
                .andExpect(jsonPath("$.storico[0].ridotta").value(true))
                .andExpect(jsonPath("$.storico[0].giorniDiRitardo").value(6))
                .andExpect(jsonPath("$.storico[0].nota").value("pioggia durante la fioritura"));

        // Nell'outbox: una riga, poi pubblicata dal publisher
        UUID eventId = eventoDellOrdine(ordine);
        await().atMost(Duration.ofSeconds(20)).until(() -> pubblicato(eventId));

        // Su Kafka: chiave = botanica, busta JSON, nessun header con tipi Java
        ConsumerRecord<String, String> messaggio = leggiDaKafka(eventId, Duration.ofSeconds(20)).getFirst();
        assertThat(messaggio.key()).isEqualTo("lavanda");
        assertThat(messaggio.headers().lastHeader("__TypeId__")).isNull();

        JsonNode busta = json.readTree(messaggio.value());
        assertThat(busta.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(busta.path("tipo").asText()).isEqualTo("ConsegnaRegistrata");
        assertThat(busta.path("versione").asInt()).isEqualTo(1);
        assertThat(busta.path("occorsoIl").asText()).isEqualTo("2026-08-06T08:30:00Z");
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("ordineId").asText()).isEqualTo(ordine.toString());
        assertThat(payload.path("edizioneId").asText()).isEqualTo(edizione.toString());
        assertThat(payload.path("botanica").asText()).isEqualTo("lavanda");
        assertThat(payload.path("origine").asText()).isEqualTo("Alta Val Varaita (Alpi Cozie)");
        assertThat(payload.path("kgOrdinati").decimalValue()).isEqualByComparingTo("12");
        assertThat(payload.path("kgArrivati").decimalValue()).isEqualByComparingTo("10.8");
        assertThat(payload.path("esitoQualita").asText()).isEqualTo("CONFORME");
        assertThat(payload.path("consegnataIl").asText()).isEqualTo("2026-08-06");
    }

    @Test
    void gliEventiDellaStessaBotanicaFinisconoNellaStessaPartizione() throws Exception {
        UUID primo = creaOrdine("lavanda", "3", UUID.randomUUID());
        UUID secondo = creaOrdine("lavanda", "4", UUID.randomUUID());
        registraConsegna(primo, "3", "2026-08-01").andExpect(status().isCreated());
        registraConsegna(secondo, "4", "2026-08-02").andExpect(status().isCreated());

        int partizionePrimo = leggiDaKafka(eventoDellOrdine(primo), Duration.ofSeconds(20)).getFirst().partition();
        int partizioneSecondo = leggiDaKafka(eventoDellOrdine(secondo), Duration.ofSeconds(20)).getFirst().partition();

        assertThat(partizioneSecondo).isEqualTo(partizionePrimo);
    }

    // ---------------------------------------------------------------- consegne rifiutate: niente lotto, niente evento

    @Test
    void unaSecondaConsegnaRisponde409ELasciaUnSoloEvento() throws Exception {
        UUID ordine = creaOrdine("lavanda", "12", UUID.randomUUID());
        registraConsegna(ordine, "10.8", "2026-08-06").andExpect(status().isCreated());

        registraConsegna(ordine, "1.2", "2026-08-06")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Ordine già consegnato"));

        assertThat(righeOutbox(ordine)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from lotto_di_botanica where ordine_id = ?", Integer.class,
                ordine)).isEqualTo(1);
    }

    @Test
    void piuChiloDiQuelliOrdinatiRispondono422SenzaLottoNeEvento() throws Exception {
        UUID ordine = creaOrdine("lavanda", "12", UUID.randomUUID());

        registraConsegna(ordine, "12.5", "2026-08-06")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("12,5 kg")));

        assertThat(righeOutbox(ordine)).isZero();
        assertThat(jdbc.queryForObject("select stato from ordine_di_acquisto where id = ?", String.class, ordine))
                .isEqualTo("APERTO");
        assertThat(jdbc.queryForObject("select count(*) from lotto_di_botanica where ordine_id = ?", Integer.class,
                ordine)).isZero();
    }

    /**
     * L'altra metà della garanzia: se la riga di outbox non entra nel database,
     * non entra nemmeno la consegna. Un trigger di prova fa fallire gli
     * INSERT nell'outbox per la genziana.
     */
    @Test
    void seLOutboxNonSiScriveSaltaAncheLaConsegna() throws Exception {
        UUID ordine = creaOrdine("genziana", "5", UUID.randomUUID());
        int storicoPrima = vociDiStorico();
        jdbc.execute("""
                create or replace function outbox_rotta() returns trigger as $$
                begin raise exception 'outbox non disponibile (trigger di prova)'; end $$ language plpgsql""");
        jdbc.execute("create trigger outbox_rotta before insert on outbox for each row"
                + " when (new.chiave = 'genziana') execute function outbox_rotta()");
        try {
            assertThatThrownBy(() -> servizio.registraConsegna(ordine, new DatiConsegna(new BigDecimal("5"),
                    LocalDate.of(2026, 8, 6), LocalDate.of(2027, 8, 31), EsitoQualita.CONFORME, null)))
                    .hasStackTraceContaining("outbox non disponibile");
        } finally {
            jdbc.execute("drop trigger outbox_rotta on outbox");
        }

        assertThat(jdbc.queryForObject("select stato from ordine_di_acquisto where id = ?", String.class, ordine))
                .isEqualTo("APERTO");
        assertThat(jdbc.queryForObject("select count(*) from lotto_di_botanica where ordine_id = ?", Integer.class,
                ordine)).isZero();
        assertThat(vociDiStorico()).isEqualTo(storicoPrima);
        assertThat(righeOutbox(ordine)).isZero();
    }

    // ---------------------------------------------------------------- transactional outbox con Kafka giù

    @Test
    void conKafkaGiuLaConsegnaSiRegistraELEventoParteQuandoTorna() throws Exception {
        UUID ordine = creaOrdine("lavanda", "12", UUID.randomUUID());

        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
        UUID eventId;
        try {
            // Elena registra la consegna: la risposta non aspetta Kafka
            long inizio = System.nanoTime();
            registraConsegna(ordine, "10.8", "2026-08-06").andExpect(status().isCreated());
            assertThat(Duration.ofNanos(System.nanoTime() - inizio)).isLessThan(Duration.ofSeconds(2));

            // L'evento è nell'outbox, il publisher ci prova e non ci riesce
            eventId = eventoDellOrdine(ordine);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> tentativi(eventId) >= 1 && !pubblicato(eventId));
            assertThat(jdbc.queryForObject("select ultimo_errore from outbox where event_id = ?", String.class,
                    eventId)).isNotBlank();
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        // Kafka è tornato: l'evento parte da solo, senza che nessuno faccia niente
        await().atMost(Duration.ofSeconds(60)).until(() -> pubblicato(eventId));
        List<ConsumerRecord<String, String>> arrivati = leggiDaKafka(eventId, Duration.ofSeconds(30));
        // "Almeno una volta": se un invio fallito era in realtà arrivato, il
        // messaggio c'è due volte, con lo stesso eventId (il consumer lo scarta)
        assertThat(arrivati).isNotEmpty().allSatisfy(messaggio -> assertThat(messaggio.key()).isEqualTo("lavanda"));
    }

    // ---------------------------------------------------------------- dati iniziali

    @Test
    void iDatiInizialiCiSonoUnaSolaVoltaAncheRiavviando() throws Exception {
        datiIniziali.run(null);   // come un secondo avvio

        assertThat(jdbc.queryForObject("select count(*) from fornitore", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from lotto_di_botanica where id = ?", Integer.class,
                DatiIniziali.LOTTO_LAVANDA_DEI_CAMPI)).isEqualTo(1);
        mvc.perform(come(MockMvcRequestBuilders.get("/lotti/{id}", DatiIniziali.LOTTO_LAVANDA_DEI_CAMPI)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origine").value("Campi GinVault (Saluzzo, 400 m)"))
                .andExpect(jsonPath("$.kg").value(2.0))
                .andExpect(jsonPath("$.ordineId").doesNotExist());
    }

    // ---------------------------------------------------------------- supporto

    private UUID creaOrdine(String botanica, String kg, UUID edizione) throws Exception {
        String corpo = mvc.perform(come(MockMvcRequestBuilders.post("/ordini"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fornitoreId": "%s", "botanica": "%s", "kgOrdinati": %s,
                                 "consegnaAttesa": "2026-07-31", "edizioneId": "%s"}
                                """.formatted(DatiIniziali.RACCOGLITORI_VAL_VARAITA, botanica, kg, edizione)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(corpo, "$.id"));
    }

    private ResultActions registraConsegna(UUID ordine, String kg, String il) throws Exception {
        return mvc.perform(come(MockMvcRequestBuilders.post("/ordini/{id}/consegne", ordine))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"kgArrivati": %s, "consegnataIl": "%s", "scadenza": "2027-08-31",
                         "esitoQualita": "CONFORME", "nota": "pioggia durante la fioritura"}
                        """.formatted(kg, il)));
    }

    private static MockHttpServletRequestBuilder come(MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_ELENA);
    }

    /** L'eventId della riga di outbox scritta per la consegna di quest'ordine (il messaggio è JSON vero). */
    private UUID eventoDellOrdine(UUID ordine) {
        return jdbc.queryForObject("select event_id from outbox where messaggio::jsonb -> 'payload' ->> 'ordineId' = ?",
                UUID.class, ordine.toString());
    }

    private int righeOutbox(UUID ordine) {
        return jdbc.queryForObject("select count(*) from outbox where messaggio::jsonb -> 'payload' ->> 'ordineId' = ?",
                Integer.class, ordine.toString());
    }

    private boolean pubblicato(UUID eventId) {
        return jdbc.queryForObject("select pubblicato_il is not null from outbox where event_id = ?", Boolean.class,
                eventId);
    }

    private int tentativi(UUID eventId) {
        return jdbc.queryForObject("select tentativi from outbox where event_id = ?", Integer.class, eventId);
    }

    private int vociDiStorico() {
        return jdbc.queryForObject("select count(*) from fornitore_storico", Integer.class);
    }

    /** Legge magazzino.eventi dall'inizio, con un consumer group nuovo, finché trova l'evento. */
    private List<ConsumerRecord<String, String>> leggiDaKafka(UUID eventId, Duration entro) {
        Map<String, Object> configurazione = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> trovati = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configurazione)) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(entro).pollInSameThread().until(() -> {
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
}
