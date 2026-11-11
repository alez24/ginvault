package it.ginvault.edizioni;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.EdizioniDiProva;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Il consumer di Edizioni con PostgreSQL e Kafka veri (Testcontainers): le
 * ResaRicalcolata arrivano su produzione.eventi come le scrive l'outbox di
 * Produzione (la busta di docs/eventi.md), e gli eventi di Edizioni escono su
 * edizioni.eventi dall'outbox.
 *
 *  - la storia di Elena: 10,8 kg -> 540 bottiglie, TiraturaRidotta;
 *  - il lotto integrato da Marco: 12 kg -> di nuovo 600, TiraturaAumentata;
 *  - IDEMPOTENZA: la stessa resa rimandata, anche dopo una più recente, si scarta;
 *  - un ricalcolo impossibile (prenotate): l'edizione non cambia e parte
 *    RicalcoloRifiutato, il messaggio non va nel dead letter;
 *  - RETRY: con il database che per un momento rifiuta, il messaggio si riprova e passa;
 *  - DEAD LETTER: un messaggio illeggibile o per un'edizione che non esiste ci
 *    va subito, senza fermare gli altri.
 *
 * Tentativi più rapidi che in produzione: 6, da 300 ms a 1 s.
 */
@SpringBootTest(properties = {
        "edizioni.outbox.intervallo-ms=200",
        "edizioni.consumer.tentativi=6",
        "edizioni.consumer.attesa-iniziale=300ms",
        "edizioni.consumer.attesa-massima=1s"})
@ExtendWith(OutputCaptureExtension.class)
class ResaRicalcolataTest {

    private static final String PRODUZIONE = "produzione.eventi";
    private static final String EDIZIONI = "edizioni.eventi";
    private static final String DEAD_LETTER = "edizioni.dlt";

    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String CONSEGNA_DI_ELENA = "consegna ridotta: 10,8 su 12 kg";
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

    /** Come negli altri servizi: i container sono bean, li ferma Spring dopo consumer e publisher. */
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
            return TopicBuilder.name(PRODUZIONE).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic edizioniEventi() {
            return TopicBuilder.name(EDIZIONI).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic deadLetter() {
            return TopicBuilder.name(DEAD_LETTER).partitions(3).replicas(1).build();
        }

        /** Gli esiti di Pagamenti, letti dal secondo listener (provato in SagaPagamentoTest): qui restano vuoti. */
        @Bean
        NewTopic pagamentiEsiti() {
            return TopicBuilder.name("pagamenti.esiti").partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private EdizioneRepository edizioni;

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

    /** Prima di ogni test il consumer deve avere le sue 3 partizioni, se no i messaggi aspettano. */
    @BeforeEach
    void consumerPronto() {
        await().atMost(ENTRO).until(() -> listener.getListenerContainers().stream()
                .allMatch(container -> container.getAssignedPartitions().size() == 3));
    }

    // ---------------------------------------------------------------- la storia di Elena

    @Test
    void laConsegnaDiElenaArrivaFinoAllEdizione600Diventano540() throws Exception {
        UUID edizione = edizioneDa600Bottiglie(0, 0);
        UUID resa = UUID.randomUUID();

        manda(edizione, resaRicalcolata(resa, edizione, "12", "10.8", CONSEGNA_DI_ELENA));

        await().atMost(ENTRO).until(() -> tiratura(edizione) == 540);
        assertThat(bottiglie(edizione, Canale.SOCI)).isEqualTo(300);
        assertThat(bottiglie(edizione, Canale.BAR)).isEqualTo(150);
        assertThat(bottiglie(edizione, Canale.ONLINE)).isEqualTo(90);
        assertThat(esito(resa)).isEqualTo("RIDOTTA");

        ConsumerRecord<String, String> messaggio = leggi(EDIZIONI, tipo(edizione, "TiraturaRidotta")).getFirst();
        assertThat(messaggio.key()).isEqualTo(edizione.toString());
        JsonNode busta = json.readTree(messaggio.value());
        assertThat(busta.path("versione").asInt()).isEqualTo(1);
        JsonNode payload = busta.path("payload");
        assertThat(payload.path("tiraturaPrima").asInt()).isEqualTo(600);
        assertThat(payload.path("tiratura").asInt()).isEqualTo(540);
        assertThat(payload.path("quote").path("SOCI").asInt()).isEqualTo(300);
        assertThat(payload.path("quote").path("ONLINE").asInt()).isEqualTo(90);
        assertThat(payload.path("kgDisponibili").decimalValue()).isEqualByComparingTo("10.8");
        assertThat(payload.path("causa").asText()).isEqualTo(CONSEGNA_DI_ELENA);
        assertThat(payload.path("resaRicalcolataId").asText()).isEqualTo(resa.toString());
    }

    @Test
    void conIlLottoIntegratoDaMarcoSiTornaA600EParteTiraturaAumentata() throws Exception {
        UUID edizione = edizioneDa600Bottiglie(0, 0);
        manda(edizione, resaRicalcolata(UUID.randomUUID(), edizione, "12", "10.8", CONSEGNA_DI_ELENA));
        await().atMost(ENTRO).until(() -> tiratura(edizione) == 540);

        manda(edizione, resaRicalcolata(UUID.randomUUID(), edizione, "10.8", "12",
                "integrazione: 1,2 kg dal lotto a0000000-0000-4000-8000-000000000009 (" + ORIGINE + ")"));

        await().atMost(ENTRO).until(() -> tiratura(edizione) == 600);
        assertThat(bottiglie(edizione, Canale.ONLINE)).isEqualTo(150);
        JsonNode payload = json.readTree(leggi(EDIZIONI, tipo(edizione, "TiraturaAumentata")).getFirst().value())
                .path("payload");
        assertThat(payload.path("tiraturaPrima").asInt()).isEqualTo(540);
        assertThat(payload.path("tiratura").asInt()).isEqualTo(600);
        assertThat(payload.path("quote").path("ONLINE").asInt()).isEqualTo(150);
        assertThat(payload.path("causa").asText()).startsWith("integrazione: 1,2 kg");
    }

    // ---------------------------------------------------------------- idempotenza

    @Test
    void laStessaResaRimandataSiScartaAncheDopoUnaPiuRecente() throws Exception {
        UUID edizione = edizioneDa600Bottiglie(0, 0);
        UUID resaDiElena = UUID.randomUUID();
        String consegna = resaRicalcolata(resaDiElena, edizione, "12", "10.8", CONSEGNA_DI_ELENA);
        manda(edizione, consegna);
        await().atMost(ENTRO).until(() -> tiratura(edizione) == 540);
        manda(edizione, resaRicalcolata(UUID.randomUUID(), edizione, "10.8", "12", "integrazione"));
        await().atMost(ENTRO).until(() -> tiratura(edizione) == 600);

        // La resa di Elena di nuovo (il publisher di Produzione non ha avuto la conferma):
        // applicata adesso riporterebbe l'edizione a 540
        attendiLetto(manda(edizione, consegna));

        assertThat(tiratura(edizione)).isEqualTo(600);
        assertThat(jdbc.queryForObject("select count(*) from evento_elaborato where event_id = ?", Integer.class,
                resaDiElena)).isEqualTo(1);
        assertThat(righeDiOutbox(edizione, "TiraturaRidotta")).isEqualTo(1);
        assertThat(righeDiOutbox(edizione, "TiraturaAumentata")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- il ricalcolo rifiutato

    @Test
    void conTroppeBottigliePrenotateIlRicalcoloERifiutatoMaIlMessaggioNonSiPerde(CapturedOutput log)
            throws Exception {
        // 100 prenotate su 150 ONLINE, BAR tutto prenotato: si possono togliere solo 50 bottiglie, non 60
        UUID edizione = edizioneDa600Bottiglie(100, 150);
        UUID resa = UUID.randomUUID();

        manda(edizione, resaRicalcolata(resa, edizione, "12", "10.8", CONSEGNA_DI_ELENA));

        JsonNode payload = json.readTree(leggi(EDIZIONI, tipo(edizione, "RicalcoloRifiutato")).getFirst().value())
                .path("payload");
        assertThat(payload.path("motivo").asText()).isEqualTo("QUOTE_NON_RIDUCIBILI");
        assertThat(payload.path("spiegazione").asText()).contains("ne vanno tolte 60", "solo 50 non prenotate");
        assertThat(payload.path("tiratura").asInt()).isEqualTo(600);
        assertThat(payload.path("tiraturaRichiesta").asInt()).isEqualTo(540);
        assertThat(payload.path("kgRichiesti").decimalValue()).isEqualByComparingTo("10.8");
        assertThat(payload.path("quote").path("ONLINE").path("prenotate").asInt()).isEqualTo(100);
        assertThat(payload.path("resaRicalcolataId").asText()).isEqualTo(resa.toString());

        assertThat(tiratura(edizione)).isEqualTo(600);
        assertThat(esito(resa)).isEqualTo("RIFIUTATO");
        assertThat(log).contains("ricalcolo dell'edizione " + edizione + " RIFIUTATO");
        assertThat(log).doesNotContain("spostato in " + DEAD_LETTER);
    }

    // ---------------------------------------------------------------- retry e dead letter

    @Test
    void seIlDatabaseRifiutaPerUnMomentoIlMessaggioSiRiprovaEPoiPassa(CapturedOutput log) throws Exception {
        UUID edizione = edizioneDa600Bottiglie(0, 0);
        UUID resa = UUID.randomUUID();
        // Ogni nuova riga di evento_elaborato viene rifiutata: il commit fallisce e non resta niente
        jdbc.execute("alter table evento_elaborato add constraint prova_database_occupato check (false) not valid");
        try {
            manda(edizione, resaRicalcolata(resa, edizione, "12", "10.8", CONSEGNA_DI_ELENA));
            await().atMost(ENTRO).until(() -> log.getOut().contains("non elaborato (tentativo 1)"));
            assertThat(tiratura(edizione)).isEqualTo(600);
        } finally {
            jdbc.execute("alter table evento_elaborato drop constraint prova_database_occupato");
        }

        await().atMost(ENTRO).until(() -> tiratura(edizione) == 540);
        assertThat(esito(resa)).isEqualTo("RIDOTTA");
        assertThat(righeDiOutbox(edizione, "TiraturaRidotta")).isEqualTo(1);
    }

    @Test
    void unMessaggioIllegibileVaSubitoNelDeadLetterSenzaFermareGliAltri() throws Exception {
        UUID edizione = edizioneDa600Bottiglie(0, 0);
        String nonJson = "non è json " + UUID.randomUUID();
        String kgNegativi = resaRicalcolata(UUID.randomUUID(), edizione, "12", "-1", "sbagliata");
        manda(edizione, nonJson);
        manda(edizione, kgNegativi);
        // Dopo i due messaggi sbagliati, nella stessa partizione, una resa buona
        manda(edizione, resaRicalcolata(UUID.randomUUID(), edizione, "12", "10.8", CONSEGNA_DI_ELENA));

        ConsumerRecord<String, String> primo = leggi(DEAD_LETTER, letto -> letto.value().equals(nonJson)).getFirst();
        assertThat(testo(primo, "kafka_dlt-original-topic")).isEqualTo(PRODUZIONE);
        assertThat(eccezione(primo)).contains("EventoNonValido");
        assertThat(testo(primo, "kafka_dlt-exception-message")).contains("non è JSON");
        ConsumerRecord<String, String> secondo = leggi(DEAD_LETTER,
                letto -> letto.value().equals(kgNegativi)).getFirst();
        assertThat(testo(secondo, "kafka_dlt-exception-message")).contains("kgDisponibili non validi");

        await().atMost(ENTRO).until(() -> tiratura(edizione) == 540);
    }

    @Test
    void unaResaPerUnEdizioneCheNonEsisteVaNelDeadLetterSenzaNuoviTentativi(CapturedOutput log) throws Exception {
        UUID sconosciuta = UUID.randomUUID();
        String resa = resaRicalcolata(UUID.randomUUID(), sconosciuta, "12", "10.8", CONSEGNA_DI_ELENA);
        RecordMetadata inviato = manda(sconosciuta, resa);

        ConsumerRecord<String, String> morto = leggi(DEAD_LETTER, letto -> letto.value().equals(resa)).getFirst();
        assertThat(morto.partition()).isEqualTo(inviato.partition());
        assertThat(eccezione(morto)).contains("EdizioneNonTrovata");
        assertThat(testo(morto, "kafka_dlt-exception-message")).contains(sconosciuta.toString());
        // Il WARN del primo (e unico) tentativo, poi subito il dead letter
        String dove = PRODUZIONE + "-" + inviato.partition() + "@" + inviato.offset();
        assertThat(log.getOut()).contains(dove + " non elaborato (tentativo 1)")
                .doesNotContain(dove + " non elaborato (tentativo 2)");
    }

    // ---------------------------------------------------------------- aiuti: dati e messaggi

    /** L'edizione d'estate: 12 kg di lavanda a 20 g, 600 bottiglie (SOCI 300, BAR 150, ONLINE 150). */
    private UUID edizioneDa600Bottiglie(int prenotateOnline, int prenotateBar) {
        Map<Canale, Integer> quote = new EnumMap<>(Map.of(Canale.SOCI, 300, Canale.BAR, 150, Canale.ONLINE, 150));
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 2, "Lavanda",
                new BigDecimal("20"), null), 2, new BigDecimal("12"), ORIGINE, quote, Duration.ofDays(7));
        if (prenotateOnline > 0) {
            EdizioniDiProva.occupa(edizione, Canale.ONLINE, prenotateOnline);
        }
        if (prenotateBar > 0) {
            EdizioniDiProva.occupa(edizione, Canale.BAR, prenotateBar);
        }
        edizioni.save(edizione);
        return edizione.id();
    }

    /** ResaRicalcolata v1, come la scrive l'outbox di Produzione (con lottoId, che Edizioni ignora). */
    private String resaRicalcolata(UUID eventId, UUID edizione, String kgPrima, String kgDopo, String motivo)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pianoId", UUID.randomUUID());
        payload.put("edizioneId", edizione);
        payload.put("botanica", "lavanda");
        payload.put("kgPrevisti", new BigDecimal("12.000"));
        payload.put("kgDisponibiliPrima", new BigDecimal(kgPrima));
        payload.put("kgDisponibili", new BigDecimal(kgDopo));
        payload.put("ordineId", UUID.randomUUID());
        payload.put("lottoId", null);
        payload.put("motivo", motivo);
        Map<String, Object> busta = new LinkedHashMap<>();
        busta.put("eventId", eventId);
        busta.put("tipo", "ResaRicalcolata");
        busta.put("versione", 1);
        busta.put("occorsoIl", Instant.now().toString());
        busta.put("payload", payload);
        return json.writeValueAsString(busta);
    }

    private RecordMetadata manda(UUID chiave, String valore) throws Exception {
        return producer.send(PRODUZIONE, chiave.toString(), valore).get().getRecordMetadata();
    }

    // ---------------------------------------------------------------- aiuti: lettura

    private int tiratura(UUID edizione) {
        return jdbc.queryForObject("select tiratura from edizione where id = ?", Integer.class, edizione);
    }

    private int bottiglie(UUID edizione, Canale canale) {
        return jdbc.queryForObject("select bottiglie from quota where edizione_id = ? and canale = ?",
                Integer.class, edizione, canale.name());
    }

    private String esito(UUID eventId) {
        return jdbc.queryForObject("select esito from evento_elaborato where event_id = ?", String.class, eventId);
    }

    private int righeDiOutbox(UUID edizione, String tipo) {
        return jdbc.queryForObject("select count(*) from outbox where chiave = ? and tipo = ?", Integer.class,
                edizione.toString(), tipo);
    }

    private static Predicate<ConsumerRecord<String, String>> tipo(UUID edizione, String tipo) {
        return letto -> edizione.toString().equals(letto.key()) && letto.value().contains("\"tipo\":\"" + tipo + "\"");
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

    /** La classe dell'eccezione: nell'header dell'eccezione o in quello della causa. */
    private static String eccezione(ConsumerRecord<String, String> messaggio) {
        return testo(messaggio, "kafka_dlt-exception-fqcn") + " " + testo(messaggio, "kafka_dlt-exception-cause-fqcn");
    }
}
