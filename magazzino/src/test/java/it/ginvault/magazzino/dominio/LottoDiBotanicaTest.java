package it.ginvault.magazzino.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import it.ginvault.magazzino.dominio.RiservaRifiutata.Motivo;

import org.junit.jupiter.api.Test;

/**
 * Le riserve di un lotto per i piani di produzione, senza Spring né database.
 * Il lotto dei campi dell'azienda: 2 kg di lavanda, conforme, scade il 31/7/2027.
 */
class LottoDiBotanicaTest {

    private static final UUID PIANO = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final LocalDate OGGI = LocalDate.of(2026, 8, 6);
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");
    private static final String MARCO = "github:42";

    @Test
    void unaRiservaTogliKgDaQuelliLiberi() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31));

        EsitoRiserva esito = lotto.riserva(PIANO, new BigDecimal("1.2"), OGGI, MARCO, ADESSO);

        assertThat(esito.giaRiservato()).isFalse();
        assertThat(esito.kgRiservati()).isEqualByComparingTo("1.2").hasScaleOf(3);
        assertThat(esito.kgDisponibili()).isEqualByComparingTo("0.8");
        assertThat(lotto.kgDisponibili()).isEqualByComparingTo("0.8");
        assertThat(lotto.kg()).isEqualByComparingTo("2");
        assertThat(lotto.riservaDel(PIANO)).hasValueSatisfying(riserva -> {
            assertThat(riserva.riservataDa()).isEqualTo(MARCO);
            assertThat(riserva.riservataIl()).isEqualTo(ADESSO);
        });
    }

    @Test
    void laStessaRichiestaRipetutaRestituisceLaRiservaCheCe() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31));
        lotto.riserva(PIANO, new BigDecimal("1.2"), OGGI, MARCO, ADESSO);

        EsitoRiserva ripetuta = lotto.riserva(PIANO, new BigDecimal("1.200"), OGGI, MARCO, ADESSO);

        assertThat(ripetuta.giaRiservato()).isTrue();
        assertThat(ripetuta.kgDisponibili()).isEqualByComparingTo("0.8");
        assertThat(lotto.riserve()).hasSize(1);
    }

    @Test
    void ancheDopoLaScadenzaLaRichiestaRipetutaDaLaStessaRisposta() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2026, 8, 31));
        lotto.riserva(PIANO, new BigDecimal("1.2"), OGGI, MARCO, ADESSO);

        assertThat(lotto.riserva(PIANO, new BigDecimal("1.2"), LocalDate.of(2026, 9, 15), MARCO, ADESSO)
                .giaRiservato()).isTrue();
    }

    @Test
    void loStessoPianoNonPuoRiservareAltriKgDelloStessoLotto() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31));
        lotto.riserva(PIANO, new BigDecimal("1.2"), OGGI, MARCO, ADESSO);

        assertRifiuto(() -> lotto.riserva(PIANO, new BigDecimal("0.5"), OGGI, MARCO, ADESSO), Motivo.GIA_RISERVATO,
                "ha già riservato 1,2 kg");
    }

    @Test
    void nonSiRiservaPiuDiQuelloCheELibero() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31));
        lotto.riserva(PIANO, new BigDecimal("1.2"), OGGI, MARCO, ADESSO);

        assertRifiuto(() -> lotto.riserva(UUID.randomUUID(), new BigDecimal("1"), OGGI, MARCO, ADESSO),
                Motivo.KG_INSUFFICIENTI, "Il lotto ha 0,8 kg liberi: non se ne possono riservare 1");
        // Esattamente quelli liberi invece sì: il lotto resta a zero
        lotto.riserva(UUID.randomUUID(), new BigDecimal("0.8"), OGGI, MARCO, ADESSO);
        assertThat(lotto.kgDisponibili()).isEqualByComparingTo("0");
    }

    @Test
    void unLottoNonConformeOScadutoNonSiRiserva() {
        assertRifiuto(() -> lotto(EsitoQualita.NON_CONFORME, LocalDate.of(2027, 7, 31))
                .riserva(PIANO, BigDecimal.ONE, OGGI, MARCO, ADESSO), Motivo.NON_UTILIZZABILE, "controllo qualità");
        assertRifiuto(() -> lotto(EsitoQualita.CONFORME, LocalDate.of(2026, 8, 5))
                .riserva(PIANO, BigDecimal.ONE, OGGI, MARCO, ADESSO), Motivo.NON_UTILIZZABILE, "scaduto");
    }

    @Test
    void datiNonValidiNonRiservanoNiente() {
        LottoDiBotanica lotto = lotto(EsitoQualita.CONFORME, LocalDate.of(2027, 7, 31));

        assertRifiuto(() -> lotto.riserva(null, BigDecimal.ONE, OGGI, MARCO, ADESSO), Motivo.DATI_NON_VALIDI, "piano");
        assertRifiuto(() -> lotto.riserva(PIANO, BigDecimal.ZERO, OGGI, MARCO, ADESSO), Motivo.DATI_NON_VALIDI,
                "maggiori di zero");
        assertRifiuto(() -> lotto.riserva(PIANO, new BigDecimal("0.0001"), OGGI, MARCO, ADESSO),
                Motivo.DATI_NON_VALIDI, "grammo");
        assertRifiuto(() -> lotto.riserva(PIANO, BigDecimal.ONE, OGGI, " ", ADESSO), Motivo.DATI_NON_VALIDI, "chi");
        assertThat(lotto.riserve()).isEmpty();
    }

    private static LottoDiBotanica lotto(EsitoQualita esito, LocalDate scadenza) {
        return LottoDiBotanica.senzaOrdine(UUID.randomUUID(), "lavanda", "Campi GinVault (Saluzzo, 400 m)",
                new BigDecimal("2"), scadenza, esito, UUID.randomUUID(), LocalDate.of(2026, 7, 15), ADESSO);
    }

    private static void assertRifiuto(Runnable azione, Motivo motivo, String messaggio) {
        assertThatThrownBy(azione::run).isInstanceOfSatisfying(RiservaRifiutata.class, rifiuto -> {
            assertThat(rifiuto.motivo()).isEqualTo(motivo);
            assertThat(rifiuto.getMessage()).contains(messaggio);
        });
    }
}
