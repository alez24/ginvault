package it.ginvault.edizioni.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Il Value Object Tiratura da solo: min(600, grammi disponibili / grammi per bottiglia), tra 1 e 600. */
class TiraturaTest {

    private static final BigDecimal VENTI_GRAMMI = new BigDecimal("20.00");

    @Test
    void laStoriaDiMarco12kgSono600E10virgola8Sono540() {
        assertThat(Tiratura.da(kg("12"), VENTI_GRAMMI).bottiglie()).isEqualTo(600);
        assertThat(Tiratura.da(kg("10.8"), VENTI_GRAMMI).bottiglie()).isEqualTo(540);
    }

    @Test
    void siArrotondaPerDifettoUnaBottigliaAMetaNonSiVende() {
        assertThat(Tiratura.da(kg("10.81"), VENTI_GRAMMI).bottiglie()).isEqualTo(540);   // 540,5
        assertThat(Tiratura.da(kg("0.02"), VENTI_GRAMMI).bottiglie()).isEqualTo(1);
    }

    @Test
    void maiSopra600AncheConTantaBotanica() {
        assertThat(Tiratura.da(kg("20"), VENTI_GRAMMI).bottiglie()).isEqualTo(600);
        assertThat(Tiratura.da(kg("99999.999"), new BigDecimal("0.01")).bottiglie()).isEqualTo(600);
        assertThatThrownBy(() -> new Tiratura(601)).isInstanceOf(EdizioneNonValida.class);
    }

    @Test
    void almenoUnaBottiglia() {
        assertThatThrownBy(() -> Tiratura.da(kg("0.019"), VENTI_GRAMMI))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("nemmeno una bottiglia")
                .hasMessageContaining("20 g per bottiglia");
        assertThatThrownBy(() -> new Tiratura(0)).isInstanceOf(EdizioneNonValida.class);
    }

    private static BigDecimal kg(String valore) {
        return new BigDecimal(valore).setScale(3);
    }
}
