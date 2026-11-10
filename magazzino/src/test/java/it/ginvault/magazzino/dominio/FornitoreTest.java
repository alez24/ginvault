package it.ginvault.magazzino.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Lo storico del fornitore e il suo riepilogo; il lotto senza ordine. */
class FornitoreTest {

    private static final UUID ID = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.randomUUID();
    private static final LocalDate ATTESA = LocalDate.of(2026, 7, 31);
    private static final LocalDate OGGI = LocalDate.of(2026, 8, 6);
    private static final Instant ADESSO = Instant.parse("2026-08-06T08:30:00Z");

    private final Fornitore fornitore = Fornitore.nuovo(ID, "Raccoglitori dell'Alta Val Varaita",
            "Alta Val Varaita (Alpi Cozie)");

    @Test
    void unFornitoreNuovoNonHaConsegne() {
        assertThat(fornitore.storico()).isEmpty();
        assertThat(fornitore.riepilogo()).isEqualTo(new Fornitore.Riepilogo(0, 0, 0, 0));
    }

    @Test
    void loStoricoContaPuntualiRidotteEInRitardo() {
        annota("12", "12", ATTESA);                   // puntuale e completa
        annota("12", "10.8", ATTESA.plusDays(6));     // in ritardo E ridotta: la consegna di Elena
        annota("5", "4", ATTESA.minusDays(1));        // puntuale ma ridotta

        assertThat(fornitore.riepilogo()).isEqualTo(new Fornitore.Riepilogo(3, 2, 1, 2));
        assertThat(fornitore.storico()).hasSize(3);

        VoceStorico elena = fornitore.storico().get(1);
        assertThat(elena.ridotta()).isTrue();
        assertThat(elena.inRitardo()).isTrue();
        assertThat(elena.giorniDiRitardo()).isEqualTo(6);
        assertThat(elena.kgArrivati()).isEqualByComparingTo("10.8");
    }

    @Test
    void loStoricoNonSiModificaDaFuori() {
        annota("12", "12", ATTESA);

        assertThatThrownBy(() -> fornitore.storico().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nonSiAnnotaLaConsegnaDiUnAltroFornitoreONonArrivata() {
        OrdineDiAcquisto altrui = OrdineDiAcquisto.nuovo(UUID.randomUUID(), "lavanda", BigDecimal.TEN, ATTESA,
                EDIZIONE, ADESSO);
        OrdineDiAcquisto aperto = OrdineDiAcquisto.nuovo(ID, "lavanda", BigDecimal.TEN, ATTESA, EDIZIONE, ADESSO);

        assertThatThrownBy(() -> fornitore.annotaConsegna(altrui)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fornitore.annotaConsegna(aperto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ilLottoDeiCampiNonHaOrdine() {
        LottoDiBotanica lotto = LottoDiBotanica.senzaOrdine(UUID.randomUUID(), "Lavanda", "Campi GinVault (Saluzzo, 400 m)",
                new BigDecimal("2"), LocalDate.of(2027, 7, 31), EsitoQualita.CONFORME, UUID.randomUUID(),
                LocalDate.of(2026, 7, 15), ADESSO);

        assertThat(lotto.ordineId()).isNull();
        assertThat(lotto.botanica()).isEqualTo("lavanda");
        assertThat(lotto.kg()).isEqualByComparingTo("2").hasScaleOf(3);
        assertThat(lotto.utilizzabile(OGGI)).isTrue();
        assertThat(lotto.utilizzabile(LocalDate.of(2027, 8, 1))).isFalse();   // scaduto
    }

    private void annota(String ordinati, String arrivati, LocalDate il) {
        OrdineDiAcquisto ordine = OrdineDiAcquisto.nuovo(ID, "lavanda", new BigDecimal(ordinati), ATTESA, EDIZIONE,
                ADESSO);
        ordine.registraConsegna(new DatiConsegna(new BigDecimal(arrivati), il, il.plusYears(1),
                EsitoQualita.CONFORME, null), fornitore.origine(), OGGI, ADESSO);
        fornitore.annotaConsegna(ordine);
    }
}
