package it.ginvault.magazzino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import it.ginvault.giacenze.v1.AnnullaRiservaRequest;
import it.ginvault.giacenze.v1.AnnullaRiservaResponse;
import it.ginvault.giacenze.v1.DisponibilitaRequest;
import it.ginvault.giacenze.v1.DisponibilitaResponse;
import it.ginvault.giacenze.v1.GiacenzeGrpc;
import it.ginvault.giacenze.v1.GiacenzeGrpc.GiacenzeBlockingStub;
import it.ginvault.giacenze.v1.LottoDisponibile;
import it.ginvault.giacenze.v1.RiservaLottoRequest;
import it.ginvault.giacenze.v1.RiservaLottoResponse;
import it.ginvault.magazzino.dominio.EsitoQualita;
import it.ginvault.magazzino.dominio.LottoDiBotanica;
import it.ginvault.magazzino.dominio.LottoRepository;
import it.ginvault.magazzino.infrastruttura.datiiniziali.DatiIniziali;
import it.ginvault.magazzino.infrastruttura.grpc.ServerGiacenze;
import it.ginvault.magazzino.sicurezza.GatewayDiProva;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Il server gRPC Giacenze VERO: Magazzino con il suo PostgreSQL
 * (Testcontainers), il server Netty su una porta libera e un client gRPC
 * come quello di Produzione, che manda il JWT nel metadata authorization.
 *
 *  - Disponibilita: solo i lotti utilizzabili, con i kg liberi;
 *  - RiservaLotto: riserva, idempotenza, kg insufficienti, lotto non
 *    utilizzabile o inesistente, due riserve nello stesso istante;
 *  - AnnullaRiserva: libera i kg, è idempotente, si può riservare di nuovo;
 *  - sicurezza: senza JWT, con un JWT falso o con il ruolo sbagliato non passa.
 *
 * Kafka non serve: le riserve non pubblicano eventi (l'outbox resta vuota).
 */
@SpringBootTest(properties = {"magazzino.grpc.porta=0", "magazzino.outbox.intervallo-ms=3600000"})
class GiacenzeGrpcTest {

    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String CAMPI = "Campi GinVault (Saluzzo, 400 m)";

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final String JWT_DI_MARCO = GATEWAY.token("DISTILLATORE");
    private static final String JWT_DI_ELENA = GATEWAY.token("RESP_ACQUISTI");

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

        /** Il 6 agosto 2026, il giorno della consegna di Elena. */
        @Bean
        @Primary
        Clock orologioFisso() {
            return Clock.fixed(ADESSO, ZoneId.of("Europe/Rome"));
        }
    }

    @Autowired
    private ServerGiacenze server;

    @Autowired
    private LottoRepository lotti;

    @Autowired
    private JdbcTemplate jdbc;

    private ManagedChannel canale;

    /** Ogni test usa una botanica sua: i lotti degli altri test non si vedono. */
    private String botanica;

    @BeforeEach
    void collegati() {
        canale = Grpc.newChannelBuilder("localhost:" + server.porta(), InsecureChannelCredentials.create()).build();
        botanica = "lavanda-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void scollegati() throws InterruptedException {
        canale.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------- Disponibilita

    @Test
    void disponibilitaMostraSoloILottiUtilizzabiliConIKgLiberi() {
        UUID valVaraita = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        UUID campi = lotto(CAMPI, "2", EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31), LocalDate.of(2026, 7, 15));
        lotto(ORIGINE, "5", EsitoQualita.NON_CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 1));
        lotto(ORIGINE, "4", EsitoQualita.CONFORME, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 6, 1));

        DisponibilitaResponse risposta = come(JWT_DI_MARCO)
                .disponibilita(DisponibilitaRequest.newBuilder().setBotanica(" " + botanica.toUpperCase() + " ").build());

        assertThat(risposta.getBotanica()).isEqualTo(botanica);
        assertThat(risposta.getLottiList()).extracting(LottoDisponibile::getLottoId)
                .containsExactly(valVaraita.toString(), campi.toString());
        LottoDisponibile deiCampi = risposta.getLotti(1);
        assertThat(deiCampi.getOrigine()).isEqualTo(CAMPI);
        assertThat(deiCampi.getKg()).isEqualTo("2.000");
        assertThat(deiCampi.getKgDisponibili()).isEqualTo("2.000");
        assertThat(deiCampi.getScadenza()).isEqualTo("2027-07-31");
        assertThat(deiCampi.getFornitore()).isEqualTo("Campi GinVault");
        assertThat(risposta.getKgDisponibiliTotali()).isEqualTo("5.000");
    }

    @Test
    void ilLottoDeiCampiDeiDatiInizialiCe() {
        DisponibilitaResponse risposta = come(JWT_DI_ELENA)
                .disponibilita(DisponibilitaRequest.newBuilder().setBotanica("Lavanda").build());

        assertThat(risposta.getLottiList()).anySatisfy(lotto -> {
            assertThat(lotto.getLottoId()).isEqualTo(DatiIniziali.LOTTO_LAVANDA_DEI_CAMPI.toString());
            assertThat(lotto.getOrigine()).isEqualTo(CAMPI);
        });
    }

    @Test
    void unaBotanicaVuotaEUnArgomentoNonValido() {
        assertStato(() -> come(JWT_DI_MARCO).disponibilita(DisponibilitaRequest.newBuilder().setBotanica(" ").build()),
                Status.Code.INVALID_ARGUMENT, "botanica");
    }

    // ------------------------------------------------------------- RiservaLotto

    @Test
    void marcoRiservaIKgCheMancanoEDisponibilitaLiMostraTolti() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        UUID piano = UUID.randomUUID();

        RiservaLottoResponse risposta = come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "1.2"));

        assertThat(risposta.getGiaRiservato()).isFalse();
        assertThat(risposta.getKgRiservati()).isEqualTo("1.200");
        assertThat(risposta.getKgDisponibili()).isEqualTo("1.800");
        assertThat(risposta.getOrigine()).isEqualTo(ORIGINE);
        assertThat(jdbc.queryForObject("select riservata_da from lotto_riserva where lotto_id = ? and piano_id = ?",
                String.class, lotto, piano)).isEqualTo("github:42");
        assertThat(come(JWT_DI_MARCO).disponibilita(DisponibilitaRequest.newBuilder().setBotanica(botanica).build())
                .getLotti(0).getKgDisponibili()).isEqualTo("1.800");
    }

    @Test
    void laStessaRiservaRipetutaNonRiservaDueVolte() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        UUID piano = UUID.randomUUID();
        come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "1.2"));

        RiservaLottoResponse ripetuta = come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "1.200"));

        assertThat(ripetuta.getGiaRiservato()).isTrue();
        assertThat(ripetuta.getKgDisponibili()).isEqualTo("1.800");
        assertThat(righeRiserva(lotto)).isEqualTo(1);
        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "0.5")), Status.Code.ALREADY_EXISTS,
                "ha già riservato 1,2 kg");
    }

    @Test
    void unLottoTuttoRiservatoResta0InDisponibilitaEPoiSiRifiuta() {
        UUID lotto = lotto(ORIGINE, "2", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        come(JWT_DI_MARCO).riservaLotto(riserva(lotto, UUID.randomUUID(), "2"));

        assertThat(come(JWT_DI_MARCO).disponibilita(DisponibilitaRequest.newBuilder().setBotanica(botanica).build())
                .getLotti(0).getKgDisponibili()).isEqualTo("0.000");
        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(riserva(lotto, UUID.randomUUID(), "0.1")),
                Status.Code.FAILED_PRECONDITION, "Il lotto ha 0 kg liberi");
    }

    @Test
    void annullareUnaRiservaLiberaIKgEDueVolteNonFaDanni() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        UUID piano = UUID.randomUUID();
        UUID altroPiano = UUID.randomUUID();
        come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "1.2"));
        come(JWT_DI_MARCO).riservaLotto(riserva(lotto, altroPiano, "0.5"));

        AnnullaRiservaResponse annullata = come(JWT_DI_MARCO).annullaRiserva(annulla(lotto, piano));

        assertThat(annullata.getNessunaRiserva()).isFalse();
        assertThat(annullata.getKgLiberati()).isEqualTo("1.200");
        assertThat(annullata.getKgDisponibili()).isEqualTo("2.500");
        // la riserva dell'altro piano resta
        assertThat(jdbc.queryForList("select piano_id from lotto_riserva where lotto_id = ?", UUID.class, lotto))
                .containsExactly(altroPiano);

        // Ripetuta (la compensazione riprovata): OK, niente da liberare
        AnnullaRiservaResponse ripetuta = come(JWT_DI_MARCO).annullaRiserva(annulla(lotto, piano));
        assertThat(ripetuta.getNessunaRiserva()).isTrue();
        assertThat(ripetuta.getKgLiberati()).isEqualTo("0.000");
        assertThat(ripetuta.getKgDisponibili()).isEqualTo("2.500");

        // I kg liberati si possono riservare di nuovo, anche con altri kg
        assertThat(come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "2")).getGiaRiservato()).isFalse();
        assertThat(righeRiserva(lotto)).isEqualTo(2);
    }

    @Test
    void annullareSuUnLottoCheNonEsisteOSenzaIlRuoloNonSiPuo() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        UUID piano = UUID.randomUUID();
        come(JWT_DI_MARCO).riservaLotto(riserva(lotto, piano, "1"));

        assertStato(() -> come(JWT_DI_MARCO).annullaRiserva(annulla(UUID.randomUUID(), piano)),
                Status.Code.NOT_FOUND, "non trovato");
        assertStato(() -> come(JWT_DI_MARCO).annullaRiserva(AnnullaRiservaRequest.newBuilder()
                        .setLottoId(lotto.toString()).setPianoId("piano-1").build()),
                Status.Code.INVALID_ARGUMENT, "piano_id non è un UUID");
        assertStato(() -> come(JWT_DI_ELENA).annullaRiserva(annulla(lotto, piano)),
                Status.Code.PERMISSION_DENIED, "ROLE_DISTILLATORE");
        assertThat(righeRiserva(lotto)).isEqualTo(1);
    }

    @Test
    void lottiNonUtilizzabiliOInesistentiNonSiRiservano() {
        UUID nonConforme = lotto(ORIGINE, "5", EsitoQualita.NON_CONFORME, LocalDate.of(2027, 8, 31),
                LocalDate.of(2026, 8, 1));

        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(riserva(nonConforme, UUID.randomUUID(), "1")),
                Status.Code.FAILED_PRECONDITION, "controllo qualità");
        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(riserva(UUID.randomUUID(), UUID.randomUUID(), "1")),
                Status.Code.NOT_FOUND, "non trovato");
    }

    @Test
    void idEKgScrittiMaleSonoArgomentiNonValidi() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));

        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(RiservaLottoRequest.newBuilder().setLottoId("lavanda")
                .setPianoId(UUID.randomUUID().toString()).setKg("1").build()), Status.Code.INVALID_ARGUMENT, "lotto_id");
        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(RiservaLottoRequest.newBuilder()
                .setLottoId(lotto.toString()).setPianoId(UUID.randomUUID().toString()).setKg("1,2").build()),
                Status.Code.INVALID_ARGUMENT, "punto");
        assertStato(() -> come(JWT_DI_MARCO).riservaLotto(riserva(lotto, UUID.randomUUID(), "-1")),
                Status.Code.INVALID_ARGUMENT, "maggiori di zero");
        assertThat(righeRiserva(lotto)).isZero();
    }

    @Test
    void dueRiserveNelloStessoIstanteNonSuperanoIKgDelLotto() throws Exception {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));
        CountDownLatch via = new CountDownLatch(1);
        List<Callable<Status.Code>> riserve = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            riserve.add(() -> {
                via.await();
                try {
                    come(JWT_DI_MARCO).riservaLotto(riserva(lotto, UUID.randomUUID(), "2"));
                    return Status.Code.OK;
                } catch (StatusRuntimeException errore) {
                    return errore.getStatus().getCode();
                }
            });
        }

        ExecutorService esecutore = Executors.newFixedThreadPool(2);
        try {
            List<Future<Status.Code>> esiti = riserve.stream().map(esecutore::submit).toList();
            via.countDown();
            List<Status.Code> codici = new ArrayList<>();
            for (Future<Status.Code> esito : esiti) {
                codici.add(esito.get(10, TimeUnit.SECONDS));
            }

            // Una passa; l'altra trova i kg finiti (FAILED_PRECONDITION) o perde la gara della @Version (ABORTED)
            assertThat(codici).containsOnlyOnce(Status.Code.OK);
            assertThat(codici).anyMatch(codice -> codice == Status.Code.FAILED_PRECONDITION
                    || codice == Status.Code.ABORTED);
        } finally {
            esecutore.shutdownNow();
        }
        assertThat(righeRiserva(lotto)).isEqualTo(1);
    }

    // ------------------------------------------------------------- sicurezza

    @Test
    void senzaJwtOConUnJwtFalsoNonSiPassa() {
        GiacenzeBlockingStub senzaJwt = GiacenzeGrpc.newBlockingStub(canale).withDeadlineAfter(2, TimeUnit.SECONDS);

        assertStato(() -> senzaJwt.disponibilita(DisponibilitaRequest.newBuilder().setBotanica("lavanda").build()),
                Status.Code.UNAUTHENTICATED, "authorization");
        assertStato(() -> come(GATEWAY.tokenConFirmaFalsa("DISTILLATORE"))
                .disponibilita(DisponibilitaRequest.newBuilder().setBotanica("lavanda").build()),
                Status.Code.UNAUTHENTICATED, "JWT non valido");
        assertStato(() -> come(GATEWAY.tokenScaduto("DISTILLATORE"))
                .disponibilita(DisponibilitaRequest.newBuilder().setBotanica("lavanda").build()),
                Status.Code.UNAUTHENTICATED, "JWT non valido");
    }

    @Test
    void elenaVedeLaDisponibilitaMaNonRiserva() {
        UUID lotto = lotto(ORIGINE, "3", EsitoQualita.CONFORME, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 6));

        assertThat(come(JWT_DI_ELENA).disponibilita(DisponibilitaRequest.newBuilder().setBotanica(botanica).build())
                .getLottiCount()).isEqualTo(1);
        assertStato(() -> come(JWT_DI_ELENA).riservaLotto(riserva(lotto, UUID.randomUUID(), "1")),
                Status.Code.PERMISSION_DENIED, "ROLE_DISTILLATORE");
        assertStato(() -> come(GATEWAY.token("SOCIO"))
                .disponibilita(DisponibilitaRequest.newBuilder().setBotanica(botanica).build()),
                Status.Code.PERMISSION_DENIED, "ruolo");
        assertThat(righeRiserva(lotto)).isZero();
    }

    // ------------------------------------------------------------- aiuti

    /** Uno stub come quello di Produzione: deadline di 2 secondi e JWT nel metadata. */
    private GiacenzeBlockingStub come(String jwt) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + jwt);
        return GiacenzeGrpc.newBlockingStub(canale)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    private UUID lotto(String origine, String kg, EsitoQualita esito, LocalDate scadenza, LocalDate ricevutoIl) {
        UUID id = UUID.randomUUID();
        lotti.save(LottoDiBotanica.senzaOrdine(id, botanica, origine, new BigDecimal(kg), scadenza, esito,
                DatiIniziali.CAMPI_GINVAULT, ricevutoIl, ADESSO));
        return id;
    }

    private static RiservaLottoRequest riserva(UUID lotto, UUID piano, String kg) {
        return RiservaLottoRequest.newBuilder()
                .setLottoId(lotto.toString())
                .setPianoId(piano.toString())
                .setKg(kg)
                .build();
    }

    private static AnnullaRiservaRequest annulla(UUID lotto, UUID piano) {
        return AnnullaRiservaRequest.newBuilder().setLottoId(lotto.toString()).setPianoId(piano.toString()).build();
    }

    private int righeRiserva(UUID lotto) {
        return jdbc.queryForObject("select count(*) from lotto_riserva where lotto_id = ?", Integer.class, lotto);
    }

    private static void assertStato(Runnable chiamata, Status.Code codice, String descrizione) {
        assertThatThrownBy(chiamata::run).isInstanceOfSatisfying(StatusRuntimeException.class, errore -> {
            assertThat(errore.getStatus().getCode()).isEqualTo(codice);
            assertThat(errore.getStatus().getDescription()).contains(descrizione);
        });
    }
}
