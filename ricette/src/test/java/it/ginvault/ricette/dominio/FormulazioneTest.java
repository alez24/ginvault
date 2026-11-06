package it.ginvault.ricette.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Test di dominio dell'aggregato Formulazione: niente Spring, niente database,
 * solo le regole. Il filo conduttore è la storia di Marco: tre prove con la
 * lavanda dell'anno prima, il panel sceglie la seconda.
 */
class FormulazioneTest {

    private static final List<Dose> PROVA_1 = List.of(dose("lavanda", "18"), dose("ginepro", "12"), dose("coriandolo", "4"));
    private static final List<Dose> PROVA_2 = List.of(dose("lavanda", "20"), dose("ginepro", "10"), dose("coriandolo", "3"));
    private static final List<Dose> PROVA_3 = List.of(dose("lavanda", "24"), dose("ginepro", "9"), dose("coriandolo", "3"));

    // ---------------------------------------------------------------- la storia di Marco

    @Test
    void marcoPreparaTreProveEIlPanelSceglieLaSeconda() {
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "Lavanda", PROVA_1);
        estate.nuovaVersione(PROVA_2);
        estate.nuovaVersione(PROVA_3);

        assertThat(estate.stato()).isEqualTo(StatoFormulazione.IN_PROVA);
        assertThat(estate.prove()).extracting(ProvaDiAssaggio::versione).containsExactly(1, 2, 3);
        assertThat(estate.prove()).allSatisfy(prova -> assertThat(estate.esitoDi(prova)).isEqualTo(EsitoProva.IN_ASSAGGIO));

        estate.scegli(2);

        assertThat(estate.stato()).isEqualTo(StatoFormulazione.SCELTA);
        assertThat(estate.provaScelta()).map(ProvaDiAssaggio::versione).contains(2);
        assertThat(estate.prove()).extracting(estate::esitoDi)
                .containsExactly(EsitoProva.ARCHIVIATA, EsitoProva.SCELTA, EsitoProva.ARCHIVIATA);
        // Il dato che servirà a Edizioni per la tiratura: 20 g di lavanda per bottiglia
        assertThat(estate.provaScelta().flatMap(prova -> prova.doseDi(estate.botanicaDiStagione())))
                .map(Dose::grammiPerBottiglia).contains(new BigDecimal("20.00"));
    }

    @Test
    void leVersioniPrecedentiRestanoIntatte() {
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", PROVA_1);
        estate.nuovaVersione(PROVA_2);
        estate.scegli(2);

        assertThat(estate.prova(1).orElseThrow().dosi()).containsExactlyElementsOf(PROVA_1);
        assertThat(estate.prova(2).orElseThrow().dosi()).containsExactlyElementsOf(PROVA_2);
    }

    @Test
    void leDosiDiUnaProvaNonSiModificanoDaFuori() {
        List<Dose> dosi = new ArrayList<>(PROVA_1);
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", dosi);

        dosi.add(dose("cardamomo", "1"));   // chi ha passato la lista la cambia dopo...

        ProvaDiAssaggio prima = estate.prova(1).orElseThrow();
        assertThat(prima.dosi()).hasSize(3);  // ...ma la prova ne ha fatto una copia
        assertThatThrownBy(() -> prima.dosi().add(dose("cardamomo", "1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- regole sugli stati

    @Test
    void dopoLaSceltaNonSiAggiungonoVersioni() {
        Formulazione estate = scelta();

        assertThatThrownBy(() -> estate.nuovaVersione(PROVA_3))
                .isInstanceOf(TransizioneNonConsentita.class)
                .hasMessageContaining("non si aggiungono più versioni");
    }

    @Test
    void ilPanelSceglieUnaVoltaSola() {
        Formulazione estate = scelta();

        assertThatThrownBy(() -> estate.scegli(1))
                .isInstanceOf(TransizioneNonConsentita.class)
                .hasMessageContaining("già scelto la versione 2");
    }

    @Test
    void nonSiSceglieUnaVersioneCheNonEsiste() {
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", PROVA_1);

        assertThatThrownBy(() -> estate.scegli(4))
                .isInstanceOf(FormulazioneNonValida.class)
                .hasMessageContaining("le prove vanno da 1 a 1");
        assertThat(estate.stato()).isEqualTo(StatoFormulazione.IN_PROVA);
    }

    @Test
    void unaFormulazioneUsataNonSiRiusaMai() {
        Formulazione estate = scelta();
        UUID edizioneEstate = UUID.randomUUID();

        estate.usaPerEdizione(edizioneEstate);

        assertThat(estate.stato()).isEqualTo(StatoFormulazione.USATA);
        assertThat(estate.edizione()).contains(edizioneEstate);
        assertThatThrownBy(() -> estate.usaPerEdizione(UUID.randomUUID()))
                .isInstanceOf(TransizioneNonConsentita.class)
                .hasMessageContaining("non si riusa mai");
        assertThatThrownBy(() -> estate.nuovaVersione(PROVA_3)).isInstanceOf(TransizioneNonConsentita.class);
        assertThatThrownBy(() -> estate.scegli(1)).isInstanceOf(TransizioneNonConsentita.class);
    }

    @Test
    void ripetereLUtilizzoPerLaStessaEdizioneNonFaDanni() {
        Formulazione estate = scelta();
        UUID edizioneEstate = UUID.randomUUID();

        estate.usaPerEdizione(edizioneEstate);
        estate.usaPerEdizione(edizioneEstate);   // es. Edizioni ritenta dopo un timeout

        assertThat(estate.stato()).isEqualTo(StatoFormulazione.USATA);
        assertThat(estate.edizione()).contains(edizioneEstate);
    }

    @Test
    void unaFormulazioneNonSceltaNonSiUsa() {
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", PROVA_1);

        assertThatThrownBy(() -> estate.usaPerEdizione(UUID.randomUUID()))
                .isInstanceOf(TransizioneNonConsentita.class)
                .hasMessageContaining("non è ancora stata scelta");
    }

    // ---------------------------------------------------------------- regole sulle dosi

    @Test
    void ogniProvaContieneLaBotanicaDiStagione() {
        List<Dose> senzaLavanda = List.of(dose("ginepro", "12"), dose("coriandolo", "4"));

        assertThatThrownBy(() -> Formulazione.nuova("Edizione d'estate", "lavanda", senzaLavanda))
                .isInstanceOf(FormulazioneNonValida.class)
                .hasMessageContaining("botanica di stagione (lavanda)");

        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", PROVA_1);
        assertThatThrownBy(() -> estate.nuovaVersione(senzaLavanda)).isInstanceOf(FormulazioneNonValida.class);
        assertThat(estate.prove()).hasSize(1);
    }

    @Test
    void unaBotanicaNonCompareDueVolteNellaStessaProva() {
        List<Dose> lavandaDoppia = List.of(dose("lavanda", "20"), dose(" LAVANDA ", "2"));

        assertThatThrownBy(() -> Formulazione.nuova("Edizione d'estate", "lavanda", lavandaDoppia))
                .isInstanceOf(FormulazioneNonValida.class)
                .hasMessageContaining("compare due volte");
    }

    @Test
    void unaProvaHaAlmenoUnaDose() {
        assertThatThrownBy(() -> Formulazione.nuova("Edizione d'estate", "lavanda", List.of()))
                .isInstanceOf(FormulazioneNonValida.class);
    }

    @Test
    void ilNomeELaBotanicaDiStagioneSonoObbligatori() {
        assertThatThrownBy(() -> Formulazione.nuova("  ", "lavanda", PROVA_1)).isInstanceOf(FormulazioneNonValida.class);
        assertThatThrownBy(() -> Formulazione.nuova("Estate", null, PROVA_1)).isInstanceOf(FormulazioneNonValida.class);
    }

    @Test
    void unaDoseHaGrammiPositiviConAlPiuDueDecimali() {
        assertThat(dose(" Lavanda ", "20")).isEqualTo(dose("lavanda", "20.0"));   // normalizzata, confronto per valore
        assertThatThrownBy(() -> dose("lavanda", "0")).isInstanceOf(FormulazioneNonValida.class);
        assertThatThrownBy(() -> dose("lavanda", "-3")).isInstanceOf(FormulazioneNonValida.class);
        assertThatThrownBy(() -> dose("lavanda", "20.125")).isInstanceOf(FormulazioneNonValida.class);
        assertThatThrownBy(() -> new Dose("lavanda", null)).isInstanceOf(FormulazioneNonValida.class);
        assertThatThrownBy(() -> dose(" ", "20")).isInstanceOf(FormulazioneNonValida.class);
    }

    // ---------------------------------------------------------------- supporto

    private static Formulazione scelta() {
        Formulazione estate = Formulazione.nuova("Edizione d'estate", "lavanda", PROVA_1);
        estate.nuovaVersione(PROVA_2);
        estate.scegli(2);
        return estate;
    }

    private static Dose dose(String botanica, String grammi) {
        return new Dose(botanica, new BigDecimal(grammi));
    }
}
