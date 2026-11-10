package it.ginvault.magazzino.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le regole dell'ordine di acquisto e della sua consegna, senza Spring né
 * database. La storia: 12 kg di lavanda attesi per il 31 luglio, ne arrivano
 * 10,8 il 6 agosto (pioggia durante la fioritura).
 */
class OrdineDiAcquistoTest {

    private static final UUID FORNITORE = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final LocalDate ATTESA = LocalDate.of(2026, 7, 31);
    private static final LocalDate ARRIVO = LocalDate.of(2026, 8, 6);
    private static final LocalDate OGGI = LocalDate.of(2026, 8, 6);
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");

    // ---------------------------------------------------------------- creazione

    @Test
    void unOrdineNuovoEAperto() {
        OrdineDiAcquisto ordine = ordine("12");

        assertThat(ordine.stato()).isEqualTo(StatoOrdine.APERTO);
        assertThat(ordine.kgOrdinati()).isEqualByComparingTo("12").hasScaleOf(3);
        assertThat(ordine.consegna()).isNull();
        assertThat(ordine.ridotta()).isFalse();
        assertThat(ordine.kgMancanti()).isEqualByComparingTo("0");
        assertThat(ordine.eventi()).isEmpty();
    }

    @Test
    void laBotanicaSiScriveSempreAlloStessoModo() {
        OrdineDiAcquisto ordine = OrdineDiAcquisto.nuovo(FORNITORE, "  Pino   Mugo ", new BigDecimal("3"), ATTESA,
                EDIZIONE, ADESSO);

        assertThat(ordine.botanica()).isEqualTo("pino mugo");
    }

    @Test
    void chiloNonValidiOppureDatiMancantiNonCreanoLOrdine() {
        assertThatThrownBy(() -> ordine("0")).isInstanceOf(OrdineNonValido.class).hasMessageContaining("maggiori di zero");
        assertThatThrownBy(() -> ordine("1.2345")).isInstanceOf(OrdineNonValido.class).hasMessageContaining("grammo");
        assertThatThrownBy(() -> OrdineDiAcquisto.nuovo(FORNITORE, "lavanda", BigDecimal.TEN, ATTESA, null, ADESSO))
                .isInstanceOf(OrdineNonValido.class).hasMessageContaining("edizione");
        assertThatThrownBy(() -> OrdineDiAcquisto.nuovo(FORNITORE, " ", BigDecimal.TEN, ATTESA, EDIZIONE, ADESSO))
                .isInstanceOf(OrdineNonValido.class);
    }

    // ---------------------------------------------------------------- la consegna ridotta di Elena

    @Test
    void laConsegnaRidottaChiudeLOrdineECreaIlLotto() {
        OrdineDiAcquisto ordine = ordine("12");

        LottoDiBotanica lotto = ordine.registraConsegna(consegna("10.8", ARRIVO), ORIGINE, OGGI, ADESSO);

        assertThat(ordine.stato()).isEqualTo(StatoOrdine.CONSEGNATO);
        assertThat(ordine.ridotta()).isTrue();
        assertThat(ordine.kgMancanti()).isEqualByComparingTo("1.2");
        assertThat(ordine.inRitardo()).isTrue();
        assertThat(ordine.giorniDiRitardo()).isEqualTo(6);
        assertThat(ordine.consegna().lottoId()).isEqualTo(lotto.id());
        assertThat(ordine.consegna().nota()).isEqualTo("pioggia durante la fioritura");

        // Il lotto porta con sé la tracciabilità
        assertThat(lotto.botanica()).isEqualTo("lavanda");
        assertThat(lotto.kg()).isEqualByComparingTo("10.8");
        assertThat(lotto.origine()).isEqualTo(ORIGINE);
        assertThat(lotto.fornitoreId()).isEqualTo(FORNITORE);
        assertThat(lotto.ordineId()).isEqualTo(ordine.id());
        assertThat(lotto.ricevutoIl()).isEqualTo(ARRIVO);
        assertThat(lotto.utilizzabile(OGGI)).isTrue();
    }

    @Test
    void laConsegnaRegistraLEventoConKgOrdinatiEArrivati() {
        OrdineDiAcquisto ordine = ordine("12");
        LottoDiBotanica lotto = ordine.registraConsegna(consegna("10.8", ARRIVO), ORIGINE, OGGI, ADESSO);

        assertThat(ordine.eventi()).singleElement().isInstanceOfSatisfying(ConsegnaRegistrata.class, evento -> {
            assertThat(evento.ordineId()).isEqualTo(ordine.id());
            assertThat(evento.edizioneId()).isEqualTo(EDIZIONE);
            assertThat(evento.lottoId()).isEqualTo(lotto.id());
            assertThat(evento.botanica()).isEqualTo("lavanda");
            assertThat(evento.origine()).isEqualTo(ORIGINE);
            assertThat(evento.kgOrdinati()).isEqualByComparingTo("12");
            assertThat(evento.kgArrivati()).isEqualByComparingTo("10.8");
            assertThat(evento.occorsoIl()).isEqualTo(ADESSO);
        });

        // Dopo la pubblicazione (Spring Data chiama eventiPubblicati) non restano eventi in attesa
        ordine.eventiPubblicati();
        assertThat(ordine.eventi()).isEmpty();
    }

    @Test
    void unaConsegnaCompletaEPuntualeNonERidottaNeInRitardo() {
        OrdineDiAcquisto ordine = ordine("12");
        ordine.registraConsegna(consegna("12", ATTESA), ORIGINE, OGGI, ADESSO);

        assertThat(ordine.ridotta()).isFalse();
        assertThat(ordine.inRitardo()).isFalse();
        assertThat(ordine.giorniDiRitardo()).isZero();
        assertThat(ordine.kgMancanti()).isEqualByComparingTo("0");
    }

    @Test
    void unaConsegnaInAnticipoNonHaGiorniDiRitardoNegativi() {
        OrdineDiAcquisto ordine = ordine("12");
        ordine.registraConsegna(consegna("12", ATTESA.minusDays(3)), ORIGINE, OGGI, ADESSO);

        assertThat(ordine.giorniDiRitardo()).isZero();
    }

    // ---------------------------------------------------------------- consegne non valide

    @Test
    void unaSecondaConsegnaVieneRifiutata() {
        OrdineDiAcquisto ordine = ordine("12");
        ordine.registraConsegna(consegna("10.8", ARRIVO), ORIGINE, OGGI, ADESSO);

        assertThatThrownBy(() -> ordine.registraConsegna(consegna("1.2", ARRIVO), ORIGINE, OGGI, ADESSO))
                .isInstanceOf(OrdineGiaConsegnato.class)
                .hasMessageContaining("10,8 kg di 12")
                .hasMessageContaining("una sola consegna");
    }

    @Test
    void piuChiloDiQuelliOrdinatiNonSiRegistrano() {
        OrdineDiAcquisto ordine = ordine("12");

        assertThatThrownBy(() -> ordine.registraConsegna(consegna("12.5", ARRIVO), ORIGINE, OGGI, ADESSO))
                .isInstanceOf(ConsegnaNonValida.class)
                .hasMessageContaining("12,5 kg ma ne erano stati ordinati 12");
        assertThat(ordine.stato()).isEqualTo(StatoOrdine.APERTO);
        assertThat(ordine.eventi()).isEmpty();
    }

    @Test
    void unaConsegnaNelFuturoNonSiRegistra() {
        OrdineDiAcquisto ordine = ordine("12");

        assertThatThrownBy(() -> ordine.registraConsegna(consegna("10.8", OGGI.plusDays(1)), ORIGINE, OGGI, ADESSO))
                .isInstanceOf(ConsegnaNonValida.class)
                .hasMessageContaining("nel futuro");
    }

    @Test
    void laScadenzaDeveVenireDopoLaConsegna() {
        OrdineDiAcquisto ordine = ordine("12");
        DatiConsegna scaduta = new DatiConsegna(new BigDecimal("10.8"), ARRIVO, ARRIVO, EsitoQualita.CONFORME, null);

        assertThatThrownBy(() -> ordine.registraConsegna(scaduta, ORIGINE, OGGI, ADESSO))
                .isInstanceOf(ConsegnaNonValida.class)
                .hasMessageContaining("scadenza");
        assertThat(ordine.consegna()).isNull();
    }

    @Test
    void zeroChiloNonSonoUnaConsegna() {
        OrdineDiAcquisto ordine = ordine("12");

        assertThatThrownBy(() -> ordine.registraConsegna(consegna("0", ARRIVO), ORIGINE, OGGI, ADESSO))
                .isInstanceOf(ConsegnaNonValida.class)
                .hasMessageContaining("maggiori di zero");
    }

    @Test
    void unLottoNonConformeRestaTracciatoMaNonUtilizzabile() {
        OrdineDiAcquisto ordine = ordine("12");
        DatiConsegna umida = new DatiConsegna(new BigDecimal("12"), ARRIVO, ARRIVO.plusYears(1),
                EsitoQualita.NON_CONFORME, "umidità troppo alta");

        LottoDiBotanica lotto = ordine.registraConsegna(umida, ORIGINE, OGGI, ADESSO);

        assertThat(lotto.esitoQualita()).isEqualTo(EsitoQualita.NON_CONFORME);
        assertThat(lotto.utilizzabile(OGGI)).isFalse();
    }

    // ---------------------------------------------------------------- supporto

    private static OrdineDiAcquisto ordine(String kg) {
        return OrdineDiAcquisto.nuovo(FORNITORE, "lavanda", new BigDecimal(kg), ATTESA, EDIZIONE, ADESSO);
    }

    private static DatiConsegna consegna(String kg, LocalDate il) {
        return new DatiConsegna(new BigDecimal(kg), il, LocalDate.of(2027, 8, 31), EsitoQualita.CONFORME,
                "  pioggia   durante la fioritura ");
    }
}
