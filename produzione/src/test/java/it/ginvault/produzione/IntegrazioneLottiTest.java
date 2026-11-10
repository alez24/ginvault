package it.ginvault.produzione;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.grpc.ConnectivityState;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import it.ginvault.giacenze.v1.DisponibilitaRequest;
import it.ginvault.giacenze.v1.DisponibilitaResponse;
import it.ginvault.giacenze.v1.GiacenzeGrpc;
import it.ginvault.giacenze.v1.LottoDisponibile;
import it.ginvault.giacenze.v1.RiservaLottoRequest;
import it.ginvault.giacenze.v1.RiservaLottoResponse;
import it.ginvault.produzione.applicazione.NuovoPiano;
import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.dominio.ConsegnaArrivata;
import it.ginvault.produzione.sicurezza.GatewayDiProva;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * La partnership con il Magazzino via gRPC VERO: Produzione con il suo
 * PostgreSQL (Testcontainers) e il suo canale gRPC, contro un magazzino finto
 * che implementa lo stesso contratto (GiacenzeImplBase di giacenze-api) su una
 * porta vera. Il magazzino finto registra le chiamate, i metadata e la
 * deadline, e può rispondere lento, con un errore o essere spento.
 *
 *  - il lotto dei campi: 409 "origine diversa", e il magazzino non riceve
 *    nessuna RiservaLotto;
 *  - un lotto dell'origine richiesta: riservato, piano di nuovo a 12 kg,
 *    ResaRicalcolata nell'outbox; rimandato -> 200, nessuna nuova riserva;
 *  - deadline di 2 secondi sul filo; magazzino lento o spento -> 503;
 *  - un rifiuto del magazzino -> 409; il JWT di Marco arriva al magazzino.
 *
 * Kafka non serve: i consumer restano spenti e ResaRicalcolata si controlla
 * nella tabella outbox (il viaggio fino a Kafka è in ProduzioneIntegrazioneTest).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "produzione.outbox.intervallo-ms=3600000"})
@AutoConfigureMockMvc
class IntegrazioneLottiTest {

    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String CAMPI = "Campi GinVault (Saluzzo, 400 m)";

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final String JWT_DI_MARCO = GATEWAY.token("DISTILLATORE");
    private static final String JWT_DI_ELENA = GATEWAY.token("RESP_ACQUISTI");

    private static final MagazzinoFinto MAGAZZINO = new MagazzinoFinto();
    private static Server server = avvia(0);
    private static final int PORTA = server.getPort();

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
        registro.add("produzione.magazzino.indirizzo", () -> "localhost:" + PORTA);
    }

    @AfterAll
    static void ferma() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        GATEWAY.ferma();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConfigurazioneDiProva {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine");
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServizioProduzione produzione;

    @Autowired
    private ManagedChannel canale;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void azzeraIlMagazzino() {
        MAGAZZINO.azzera();
    }

    // ------------------------------------------------------------- la storia

    @Test
    void ilLottoDeiCampiVieneRifiutatoPerOrigineDiversaSenzaRiservareNiente() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lottoDeiCampi = MAGAZZINO.aggiungi(CAMPI, "2.000");

        mvc.perform(integra(piano, lottoDeiCampi, "1.2", JWT_DI_MARCO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Integrazione rifiutata"))
                .andExpect(jsonPath("$.motivo").value("ORIGINE_DIVERSA"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "origine diversa: l'edizione deve restare fedele alla sua origine")))
                .andExpect(jsonPath("$.origineRichiesta").value(ORIGINE))
                .andExpect(jsonPath("$.origineLotto").value(CAMPI));

        // Produzione ha chiesto il lotto (Disponibilita) ma NON l'ha riservato
        assertThat(MAGAZZINO.disponibilitaChieste).containsExactly("lavanda");
        assertThat(MAGAZZINO.riserve).isEmpty();
        assertThat(kgDisponibili(piano)).isEqualByComparingTo("10.8");
        assertThat(righeResaRicalcolata(piano)).isEqualTo(1); // solo quella della consegna di Elena
    }

    @Test
    void unLottoDellOrigineRichiestaVieneRiservatoELaResaTornaA600Bottiglie() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lotto = MAGAZZINO.aggiungi(ORIGINE, "3.000");

        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.esito").value("RESA_RICALCOLATA"))
                .andExpect(jsonPath("$.piano.kgDisponibili").value(12.0))
                .andExpect(jsonPath("$.piano.kgMancanti").value(0.0))
                .andExpect(jsonPath("$.piano.bottiglieProducibili").value(600))
                .andExpect(jsonPath("$.piano.integrazioni[0].lottoId").value(lotto.toString()));

        assertThat(MAGAZZINO.riserve).singleElement().satisfies(riserva -> {
            assertThat(riserva.getLottoId()).isEqualTo(lotto.toString());
            assertThat(riserva.getPianoId()).isEqualTo(piano.toString());
            assertThat(riserva.getKg()).isEqualTo("1.200");
        });
        // La nuova resa parte con l'outbox, come quella della consegna
        JsonNode busta = json.readTree(jdbc.queryForObject(
                "select messaggio from outbox where tipo = 'ResaRicalcolata' and messaggio like ?", String.class,
                "%" + lotto + "%"));
        assertThat(busta.at("/payload/kgDisponibiliPrima").decimalValue()).isEqualByComparingTo("10.8");
        assertThat(busta.at("/payload/kgDisponibili").decimalValue()).isEqualByComparingTo("12");
        assertThat(busta.at("/payload/lottoId").asText()).isEqualTo(lotto.toString());
        assertThat(busta.at("/payload/ordineId").isNull()).isTrue();

        // Marco preme di nuovo: 200, nessuna nuova riserva né nuova resa
        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.esito").value("GIA_INTEGRATO"));
        assertThat(MAGAZZINO.riserve).hasSize(1);
        assertThat(righeResaRicalcolata(piano)).isEqualTo(2); // la consegna di Elena + l'integrazione
    }

    @Test
    void ilJwtDiMarcoEUnaDeadlineDiDueSecondiArrivanoAlMagazzino() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lotto = MAGAZZINO.aggiungi(ORIGINE, "3.000");

        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO)).andExpect(status().isCreated());

        assertThat(MAGAZZINO.autorizzazioni).hasSize(2).allMatch(("Bearer " + JWT_DI_MARCO)::equals);
        assertThat(MAGAZZINO.millisAllaDeadline).hasSize(2)
                .allSatisfy(millis -> assertThat(millis).isBetween(1_000L, 2_000L));
    }

    @Test
    void elenaVedeILottiDelPianoConLOrigineGiusta() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID campi = MAGAZZINO.aggiungi(CAMPI, "2.000");
        UUID valVaraita = MAGAZZINO.aggiungi(ORIGINE, "3.000");

        mvc.perform(get("/piani/{id}/lotti", piano).header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_DI_ELENA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kgMancanti").value(1.2))
                .andExpect(jsonPath("$.lotti[0].lottoId").value(campi.toString()))
                .andExpect(jsonPath("$.lotti[0].stessaOrigine").value(false))
                .andExpect(jsonPath("$.lotti[1].lottoId").value(valVaraita.toString()))
                .andExpect(jsonPath("$.lotti[1].stessaOrigine").value(true));
        assertThat(MAGAZZINO.autorizzazioni).containsExactly("Bearer " + JWT_DI_ELENA);
    }

    // ------------------------------------------------------------- quando il magazzino non collabora

    @Test
    void conIlMagazzinoLentoDopoDueSecondiRisponde503() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lotto = MAGAZZINO.aggiungi(ORIGINE, "3.000");
        MAGAZZINO.ritardo = Duration.ofMillis(3_000);

        long inizio = System.nanoTime();
        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.detail").value("Il magazzino non ha risposto entro 2 secondi: riprova fra poco"));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inizio);

        assertThat(millis).isBetween(1_900L, 2_900L);
        assertThat(MAGAZZINO.riserve).isEmpty();
        assertThat(kgDisponibili(piano)).isEqualByComparingTo("10.8");
    }

    @Test
    void conIlMagazzinoSpentoRisponde503() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lotto = MAGAZZINO.aggiungi(ORIGINE, "3.000");
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        try {
            mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("Il magazzino non è raggiungibile: riprova fra poco"));
            assertThat(kgDisponibili(piano)).isEqualByComparingTo("10.8");
        } finally {
            // Il magazzino torna sulla stessa porta: il canale si ricollega da solo
            server = avvia(PORTA);
            canale.resetConnectBackoff();
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> canale.getState(true) == ConnectivityState.READY);
        }
        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO)).andExpect(status().isCreated());
    }

    @Test
    void unRifiutoDelMagazzinoDiventa409ElPianoNonCambia() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();
        UUID lotto = MAGAZZINO.aggiungi(ORIGINE, "3.000");
        MAGAZZINO.erroreRiserva = Status.FAILED_PRECONDITION
                .withDescription("Il lotto ha 0,8 kg liberi: non se ne possono riservare 1,2");

        mvc.perform(integra(piano, lotto, "1.2", JWT_DI_MARCO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("RISERVA_RIFIUTATA"))
                .andExpect(jsonPath("$.statoGrpc").value("FAILED_PRECONDITION"))
                .andExpect(jsonPath("$.detail").value("Il lotto ha 0,8 kg liberi: non se ne possono riservare 1,2"));

        assertThat(kgDisponibili(piano)).isEqualByComparingTo("10.8");
    }

    @Test
    void unLottoCheIlMagazzinoNonHaRisponde409() throws Exception {
        UUID piano = pianoDopoLaConsegnaDiElena();

        mvc.perform(integra(piano, UUID.randomUUID(), "1.2", JWT_DI_MARCO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("LOTTO_NON_DISPONIBILE"));
        assertThat(MAGAZZINO.riserve).isEmpty();
    }

    @Test
    void unPianoCheNonEsisteRisponde404SenzaChiamareIlMagazzino() throws Exception {
        mvc.perform(integra(UUID.randomUUID(), UUID.randomUUID(), "1.2", JWT_DI_MARCO))
                .andExpect(status().isNotFound());
        assertThat(MAGAZZINO.disponibilitaChieste).isEmpty();
    }

    // ------------------------------------------------------------- aiuti

    /** L'edizione d'estate (12 kg, 600 bottiglie) dopo la consegna di Elena: 10,8 kg, 540 bottiglie. */
    private UUID pianoDopoLaConsegnaDiElena() {
        UUID edizione = UUID.randomUUID();
        produzione.creaPiano(new NuovoPiano(edizione, UUID.randomUUID(), 2, "lavanda", new BigDecimal("20"),
                ORIGINE, new BigDecimal("12"), 600));
        produzione.registraConsegna(edizione, new ConsegnaArrivata(UUID.randomUUID(), UUID.randomUUID(), "lavanda",
                ORIGINE, new BigDecimal("12"), new BigDecimal("10.8"), "CONFORME", LocalDate.of(2026, 8, 6)));
        return produzione.piani(edizione).getFirst().id();
    }

    private static RequestBuilder integra(UUID piano, UUID lotto, String kg, String jwt) {
        return post("/piani/{id}/integrazioni", piano)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lottoId\": \"" + lotto + "\", \"kg\": " + kg + "}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
    }

    private BigDecimal kgDisponibili(UUID piano) {
        return jdbc.queryForObject("select kg_disponibili from piano_di_produzione where id = ?", BigDecimal.class,
                piano);
    }

    private int righeResaRicalcolata(UUID piano) {
        return jdbc.queryForObject("select count(*) from outbox where tipo = 'ResaRicalcolata' and messaggio like ?",
                Integer.class, "%" + piano + "%");
    }

    private static Server avvia(int porta) {
        try {
            return Grpc.newServerBuilderForPort(porta, InsecureServerCredentials.create())
                    .addService(ServerInterceptors.intercept(MAGAZZINO, MAGAZZINO.registraMetadata()))
                    .build()
                    .start();
        } catch (IOException errore) {
            throw new UncheckedIOException(errore);
        }
    }

    /**
     * Il magazzino finto: lo stesso contratto del vero (GiacenzeImplBase),
     * con lotti, ritardo ed errore decisi dal test.
     */
    static final class MagazzinoFinto extends GiacenzeGrpc.GiacenzeImplBase {

        private static final Metadata.Key<String> AUTHORIZATION =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

        final List<LottoDisponibile> lotti = new CopyOnWriteArrayList<>();
        final List<String> disponibilitaChieste = new CopyOnWriteArrayList<>();
        final List<RiservaLottoRequest> riserve = new CopyOnWriteArrayList<>();
        final List<String> autorizzazioni = new CopyOnWriteArrayList<>();
        final List<Long> millisAllaDeadline = new CopyOnWriteArrayList<>();
        volatile Duration ritardo = Duration.ZERO;
        volatile Status erroreRiserva;

        void azzera() {
            lotti.clear();
            disponibilitaChieste.clear();
            riserve.clear();
            autorizzazioni.clear();
            millisAllaDeadline.clear();
            ritardo = Duration.ZERO;
            erroreRiserva = null;
        }

        UUID aggiungi(String origine, String kg) {
            UUID id = UUID.randomUUID();
            lotti.add(LottoDisponibile.newBuilder()
                    .setLottoId(id.toString())
                    .setBotanica("lavanda")
                    .setOrigine(origine)
                    .setKg(kg)
                    .setKgDisponibili(kg)
                    .setScadenza("2027-07-31")
                    .setFornitore("Fornitore di prova")
                    .build());
            return id;
        }

        /** Registra il metadata authorization e quanto manca alla deadline, come li riceve il magazzino vero. */
        ServerInterceptor registraMetadata() {
            return new ServerInterceptor() {
                @Override
                public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> chiamata, Metadata intestazioni,
                                                                   ServerCallHandler<Q, R> prossimo) {
                    autorizzazioni.add(String.valueOf(intestazioni.get(AUTHORIZATION)));
                    Deadline deadline = Context.current().getDeadline();
                    millisAllaDeadline.add(deadline == null ? -1L : deadline.timeRemaining(TimeUnit.MILLISECONDS));
                    return Contexts.interceptCall(Context.current(), chiamata, intestazioni, prossimo);
                }
            };
        }

        @Override
        public void disponibilita(DisponibilitaRequest richiesta, StreamObserver<DisponibilitaResponse> risposta) {
            disponibilitaChieste.add(richiesta.getBotanica());
            if (!attendi()) {
                return;
            }
            risposta.onNext(DisponibilitaResponse.newBuilder()
                    .setBotanica(richiesta.getBotanica())
                    .addAllLotti(lotti)
                    .build());
            risposta.onCompleted();
        }

        @Override
        public void riservaLotto(RiservaLottoRequest richiesta, StreamObserver<RiservaLottoResponse> risposta) {
            riserve.add(richiesta);
            Status errore = erroreRiserva;
            if (errore != null) {
                risposta.onError(errore.asRuntimeException());
                return;
            }
            LottoDisponibile lotto = lotti.stream()
                    .filter(candidato -> candidato.getLottoId().equals(richiesta.getLottoId()))
                    .findFirst()
                    .orElseThrow();
            risposta.onNext(RiservaLottoResponse.newBuilder()
                    .setLottoId(richiesta.getLottoId())
                    .setPianoId(richiesta.getPianoId())
                    .setBotanica("lavanda")
                    .setOrigine(lotto.getOrigine())
                    .setKgRiservati(richiesta.getKg())
                    .setKgDisponibili(new BigDecimal(lotto.getKgDisponibili())
                            .subtract(new BigDecimal(richiesta.getKg())).toPlainString())
                    .build());
            risposta.onCompleted();
        }

        /** @return false se nel frattempo Produzione ha smesso di aspettare (deadline scaduta) */
        private boolean attendi() {
            if (ritardo.isZero()) {
                return true;
            }
            try {
                Thread.sleep(ritardo);
            } catch (InterruptedException interrotto) {
                Thread.currentThread().interrupt();
            }
            return !Context.current().isCancelled();
        }
    }
}
