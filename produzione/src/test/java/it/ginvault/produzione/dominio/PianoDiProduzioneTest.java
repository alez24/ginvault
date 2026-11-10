package it.ginvault.produzione.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le regole del piano di produzione, senza Spring né database. La storia:
 * l'edizione d'estate prevede 12 kg di lavanda dell'Alta Val Varaita, 20 g per
 * bottiglia (600 bottiglie); per la pioggia ne arrivano 10,8.
 */
class PianoDiProduzioneTest {

    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID FORMULAZIONE = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String CAMPI = "Campi GinVault (Saluzzo, 400 m)";
    private static final LocalDate ARRIVO = LocalDate.of(2026, 8, 6);
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");

    // ---------------------------------------------------------------- creazione

    @Test
    void unPianoNuovoHaTuttiIKgPrevistiDisponibili() {
        PianoDiProduzione piano = estate();

        assertThat(piano.edizioneId()).isEqualTo(EDIZIONE);
        assertThat(piano.botanica()).isEqualTo("lavanda");
        assertThat(piano.origineRichiesta()).isEqualTo(ORIGINE);
        assertThat(piano.kgPrevisti()).isEqualByComparingTo("12").hasScaleOf(3);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.bottiglieProducibili()).isEqualTo(600);
        assertThat(piano.consegne()).isEmpty();
        assertThat(piano.eventi()).isEmpty();
    }

    @Test
    void botanicaEOrigineSiScrivonoSempreAlloStessoModo() {
        PianoDiProduzione piano = PianoDiProduzione.nuovo(EDIZIONE, FORMULAZIONE, 2, "  Pino   Mugo ",
                new BigDecimal("15"), "  Alta Val   Varaita (Alpi Cozie) ", new BigDecimal("9"), 600, ADESSO);

        assertThat(piano.botanica()).isEqualTo("pino mugo");
        assertThat(piano.origineRichiesta()).isEqualTo(ORIGINE);
    }

    @Test
    void datiMancantiONonValidiNonCreanoIlPiano() {
        assertThatThrownBy(() -> nuovo("0", ORIGINE)).isInstanceOf(PianoNonValido.class)
                .hasMessageContaining("maggiori di zero");
        assertThatThrownBy(() -> nuovo("1.2345", ORIGINE)).isInstanceOf(PianoNonValido.class)
                .hasMessageContaining("grammo");
        assertThatThrownBy(() -> nuovo("12", " ")).isInstanceOf(PianoNonValido.class)
                .hasMessageContaining("origine");
        assertThatThrownBy(() -> PianoDiProduzione.nuovo(null, FORMULAZIONE, 2, "lavanda", new BigDecimal("20"),
                ORIGINE, BigDecimal.TEN, 500, ADESSO)).isInstanceOf(PianoNonValido.class);
        assertThatThrownBy(() -> PianoDiProduzione.nuovo(EDIZIONE, FORMULAZIONE, 2, "lavanda", BigDecimal.ZERO,
                ORIGINE, BigDecimal.TEN, 500, ADESSO)).isInstanceOf(PianoNonValido.class)
                .hasMessageContaining("grammi");
    }

    // ---------------------------------------------------------------- la consegna ridotta di Elena

    @Test
    void laConsegnaRidottaAbbassaIKgDisponibiliERegistraResaRicalcolata() {
        PianoDiProduzione piano = estate();
        UUID ordine = UUID.randomUUID();

        EsitoConsegna esito = piano.registraConsegna(consegna(ordine, "lavanda", ORIGINE, "12", "10.8", "CONFORME"),
                ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_RICALCOLATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("10.8").hasScaleOf(3);
        assertThat(piano.bottiglieProducibili()).isEqualTo(540);
        assertThat(piano.consegne()).singleElement().satisfies(applicata -> {
            assertThat(applicata.ordineId()).isEqualTo(ordine);
            assertThat(applicata.kgUtilizzabili()).isEqualByComparingTo("10.8");
            assertThat(applicata.motivo()).isEqualTo("consegna ridotta: 10,8 su 12 kg");
        });
        assertThat(piano.eventi()).singleElement().isInstanceOfSatisfying(ResaRicalcolata.class, evento -> {
            assertThat(evento.edizioneId()).isEqualTo(EDIZIONE);
            assertThat(evento.pianoId()).isEqualTo(piano.id());
            assertThat(evento.kgDisponibiliPrima()).isEqualByComparingTo("12");
            assertThat(evento.kgDisponibili()).isEqualByComparingTo("10.8");
            assertThat(evento.ordineId()).isEqualTo(ordine);
            assertThat(evento.motivo()).isEqualTo("consegna ridotta: 10,8 su 12 kg");
        });
    }

    @Test
    void laConsegnaCompletaNonCambiaLaResaENonRegistraEventi() {
        PianoDiProduzione piano = estate();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "Lavanda", " alta val varaita"
                + " (alpi cozie) ", "12", "12", "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_INVARIATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.consegne()).singleElement()
                .satisfies(applicata -> assertThat(applicata.motivo()).isEqualTo("consegna completa: 12 su 12 kg"));
        assertThat(piano.eventi()).isEmpty();
    }

    @Test
    void laStessaConsegnaDueVolteContaUnaVolta() {
        PianoDiProduzione piano = estate();
        UUID ordine = UUID.randomUUID();
        piano.registraConsegna(consegna(ordine, "lavanda", ORIGINE, "12", "10.8", "CONFORME"), ADESSO);
        piano.eventiPubblicati();

        EsitoConsegna esito = piano.registraConsegna(consegna(ordine, "lavanda", ORIGINE, "12", "10.8", "CONFORME"),
                ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.GIA_REGISTRATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("10.8");
        assertThat(piano.consegne()).hasSize(1);
        assertThat(piano.eventi()).isEmpty();
    }

    // ---------------------------------------------------------------- l'edizione resta fedele alla sua origine

    @Test
    void dueOrdiniDaSeiKgUnoConsegnatoCompletoLasciano12Kg() {
        // Il secondo ordine da 6 kg è ancora aperto: Produzione non lo vede, e i
        // suoi kg restano tra i previsti non ancora coperti
        PianoDiProduzione piano = estate();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "6", "6",
                "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_INVARIATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.bottiglieProducibili()).isEqualTo(600);
        assertThat(piano.eventi()).isEmpty();
    }

    @Test
    void dueKgDaUnAltraOrigineArrivatiPrimaDellOrdineDa12NonTolgonoNiente() {
        // Aperto: l'ordine da 12 kg dall'Alta Val Varaita. Arrivano prima 2 kg dai campi
        PianoDiProduzione piano = estate();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", CAMPI, "2", "2",
                "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_INVARIATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.eventi()).isEmpty();
        assertThat(piano.consegne()).singleElement().satisfies(applicata -> {
            assertThat(applicata.kgUtilizzabili()).isEqualByComparingTo("0");
            assertThat(applicata.motivo()).startsWith("origine diversa (" + CAMPI + ")");
        });

        // Poi arriva l'ordine da 12, ridotto: contano solo i suoi 10,8 kg
        piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "12", "10.8", "CONFORME"), ADESSO);

        assertThat(piano.kgDisponibili()).isEqualByComparingTo("10.8");
    }

    @Test
    void unLottoNonConformeNonContaPerLEdizione() {
        PianoDiProduzione piano = estate();

        piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "12", "12", "NON_CONFORME"), ADESSO);

        assertThat(piano.kgDisponibili()).isEqualByComparingTo("0");
        assertThat(piano.bottiglieProducibili()).isZero();
        assertThat(piano.consegne().getFirst().motivo()).startsWith("lotto non conforme");
    }

    @Test
    void unOrdinePiuGrandeDelPrevistoContaPerQuelloCheArriva() {
        PianoDiProduzione piano = estate();

        piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "20", "1", "CONFORME"), ADESSO);

        assertThat(piano.kgDisponibili()).isEqualByComparingTo("1");
    }

    @Test
    void unOrdineInPiuDiUnAltraOrigineNonToglieNiente() {
        PianoDiProduzione piano = estate();
        piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "12", "10.8", "CONFORME"), ADESSO);
        piano.eventiPubblicati();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", CAMPI, "1.2", "1.2",
                "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_INVARIATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("10.8");
        assertThat(piano.consegne()).hasSize(2);
        assertThat(piano.eventi()).isEmpty();
    }

    @Test
    void unRiordinoDellaStessaOrigineRiportaIKgAlPrevistoMaiOltre() {
        PianoDiProduzione piano = estate();
        piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "12", "10.8", "CONFORME"), ADESSO);
        piano.eventiPubblicati();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "2", "2",
                "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.RESA_RICALCOLATA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.eventi()).singleElement().isInstanceOfSatisfying(ResaRicalcolata.class, evento -> {
            assertThat(evento.kgDisponibiliPrima()).isEqualByComparingTo("10.8");
            assertThat(evento.kgDisponibili()).isEqualByComparingTo("12");
        });
    }

    @Test
    void unaConsegnaDiUnAltraBotanicaSiIgnora() {
        PianoDiProduzione piano = estate();

        EsitoConsegna esito = piano.registraConsegna(consegna(UUID.randomUUID(), "ginepro", ORIGINE, "30", "25",
                "CONFORME"), ADESSO);

        assertThat(esito).isEqualTo(EsitoConsegna.ALTRA_BOTANICA);
        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.consegne()).isEmpty();
        assertThat(piano.eventi()).isEmpty();
    }

    @Test
    void unaConsegnaConDatiSbagliatiNonCambiaIlPiano() {
        PianoDiProduzione piano = estate();

        assertThatThrownBy(() -> piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", ORIGINE, "12", "13",
                "CONFORME"), ADESSO)).isInstanceOf(PianoNonValido.class).hasMessageContaining("più kg");
        assertThatThrownBy(() -> piano.registraConsegna(consegna(UUID.randomUUID(), "lavanda", " ", "12", "10",
                "CONFORME"), ADESSO)).isInstanceOf(PianoNonValido.class).hasMessageContaining("origine");
        assertThatThrownBy(() -> piano.registraConsegna(new ConsegnaArrivata(null, UUID.randomUUID(), "lavanda",
                ORIGINE, BigDecimal.TEN, BigDecimal.ONE, "CONFORME", ARRIVO), ADESSO))
                .isInstanceOf(PianoNonValido.class);

        assertThat(piano.kgDisponibili()).isEqualByComparingTo("12");
        assertThat(piano.consegne()).isEmpty();
    }

    @Test
    void leOriginiSiConfrontanoSenzaBadareAMaiuscoleESpazi() {
        assertThat(Origini.stessa(ORIGINE, "alta val  VARAITA (alpi cozie) ")).isTrue();
        assertThat(Origini.stessa(ORIGINE, CAMPI)).isFalse();
        assertThat(Origini.stessa(ORIGINE, null)).isFalse();
    }

    // ---------------------------------------------------------------- aiuti

    /** L'edizione d'estate: 12 kg di lavanda dell'Alta Val Varaita, 20 g per bottiglia, 600 bottiglie. */
    private static PianoDiProduzione estate() {
        return nuovo("12", ORIGINE);
    }

    private static PianoDiProduzione nuovo(String kg, String origine) {
        return PianoDiProduzione.nuovo(EDIZIONE, FORMULAZIONE, 2, "lavanda", new BigDecimal("20"), origine,
                new BigDecimal(kg), 600, ADESSO);
    }

    private static ConsegnaArrivata consegna(UUID ordine, String botanica, String origine, String ordinati,
                                             String arrivati, String esito) {
        return new ConsegnaArrivata(ordine, UUID.randomUUID(), botanica, origine, new BigDecimal(ordinati),
                new BigDecimal(arrivati), esito, ARRIVO);
    }
}
