package it.ginvault.edizioni.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;

import org.junit.jupiter.api.Test;

/**
 * Test di dominio dell'Edizione: niente Spring, niente rete. La formulazione di
 * Ricette arriva già tradotta in FormulazioneInCatalogo, come la produce l'adapter.
 *
 * Il filo conduttore è la storia di Marco: la seconda prova ha 20 g di lavanda
 * per bottiglia; con 12 kg l'edizione è di 600 bottiglie (SOCI 300, BAR 150,
 * ONLINE 150); la lavanda nuova arriva scarsa, 10,8 kg: 540 bottiglie.
 */
class EdizioneTest {

    private static final UUID FORMULAZIONE = UUID.fromString("7f1d2c3e-0000-4000-8000-000000000001");
    private static final Map<Canale, Integer> QUOTE_600 = quote(300, 150, 150);
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";

    // ---------------------------------------------------------------- creazione

    @Test
    void nasceDallaVersioneSceltaEConservaSoloIlRiferimento() {
        Edizione estate = estate();

        assertThat(estate.id()).isNotNull();
        assertThat(estate.formulazione()).isEqualTo(new RiferimentoFormulazione(FORMULAZIONE, 2));
        // l'unico dato copiato dalla formulazione: serve alla tiratura
        assertThat(estate.botanicaDiStagione()).isEqualTo("lavanda");
        assertThat(estate.grammiPerBottiglia()).isEqualByComparingTo("20");
        assertThat(estate.botanicaDisponibileKg()).isEqualByComparingTo("12");
        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
        assertThat(estate.bottigliePerCanale()).containsExactly(
                Map.entry(Canale.SOCI, 300), Map.entry(Canale.BAR, 150), Map.entry(Canale.ONLINE, 150));
        assertThat(estate.quote().values()).allSatisfy(quota -> assertThat(quota.prenotate()).isZero());
        assertThat(estate.origineBotanica()).isEqualTo(ORIGINE);
    }

    @Test
    void allaNascitaRegistraEdizioneCreataConLOrigine() {
        Edizione estate = Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), "  Alta Val Varaita   (Alpi Cozie) ",
                QUOTE_600);

        assertThat(estate.origineBotanica()).isEqualTo(ORIGINE);   // spazi in più tolti
        assertThat(estate.eventi()).singleElement().isInstanceOfSatisfying(EdizioneCreata.class, evento -> {
            assertThat(evento.edizioneId()).isEqualTo(estate.id());
            assertThat(evento.formulazione()).isEqualTo(new RiferimentoFormulazione(FORMULAZIONE, 2));
            assertThat(evento.botanicaDiStagione()).isEqualTo("lavanda");
            assertThat(evento.grammiPerBottiglia()).isEqualByComparingTo("20");
            assertThat(evento.origineBotanica()).isEqualTo(ORIGINE);
            assertThat(evento.kgPrevisti()).isEqualByComparingTo("12");
            assertThat(evento.tiratura()).isEqualTo(600);
            assertThat(evento.quote()).isEqualTo(QUOTE_600);
        });
    }

    @Test
    void senzaOrigineNonNasceLEdizione() {
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), " ", QUOTE_600))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("origine");
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), "x".repeat(101), QUOTE_600))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("100 caratteri");
    }

    @Test
    void laSommaDelleQuoteDeveEssereUgualeAllaTiratura() {
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), ORIGINE, quote(300, 150, 140)))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("La somma delle quote (590)")
                .hasMessageContaining("600 bottiglie (12 kg di lavanda a 20 g per bottiglia");
        // con 10,8 kg le stesse quote da 600 sono troppe: la tiratura è 540
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("10.8"), ORIGINE, QUOTE_600))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("540 bottiglie");
    }

    @Test
    void unaFormulazioneNonSceltaNonDiventaUnEdizione() {
        FormulazioneInCatalogo inProva = new FormulazioneInCatalogo(FORMULAZIONE, Stato.DA_SCEGLIERE, null, "lavanda",
                null, null);

        assertThatThrownBy(() -> Edizione.nuova(inProva, 2, new BigDecimal("12"), ORIGINE, QUOTE_600))
                .isInstanceOf(FormulazioneNonUtilizzabile.class)
                .hasMessageContaining("non è ancora stata scelta dal panel");
    }

    @Test
    void serveLaVersioneSceltaDalPanelNonUnAltra() {
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 1, new BigDecimal("12"), ORIGINE, QUOTE_600))
                .isInstanceOf(FormulazioneNonUtilizzabile.class)
                .hasMessageContaining("La versione 1 non è quella scelta dal panel (2)");
    }

    @Test
    void unaFormulazioneGiaUsataNonSiRiusa() {
        UUID altraEdizione = UUID.randomUUID();
        FormulazioneInCatalogo usata = new FormulazioneInCatalogo(FORMULAZIONE, Stato.USATA, 2, "lavanda",
                new BigDecimal("20"), altraEdizione);

        assertThatThrownBy(() -> Edizione.nuova(usata, 2, new BigDecimal("12"), ORIGINE, QUOTE_600))
                .isInstanceOf(FormulazioneGiaUsata.class)
                .hasMessageContaining(altraEdizione.toString())
                .hasMessageContaining("non si riusa mai");
    }

    @Test
    void senzaIGrammiDellaBotanicaDiStagioneNonSiCalcolaLaTiratura() {
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, null), 2, new BigDecimal("12"), ORIGINE, QUOTE_600))
                .isInstanceOf(FormulazioneNonUtilizzabile.class)
                .hasMessageContaining("non indica i grammi di Lavanda");
    }

    @Test
    void serveUnaQuotaPerOgniCanale() {
        Map<Canale, Integer> senzaOnline = new EnumMap<>(Canale.class);
        senzaOnline.put(Canale.SOCI, 300);
        senzaOnline.put(Canale.BAR, 300);

        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), ORIGINE, senzaOnline))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("manca ONLINE");
    }

    @Test
    void quoteNonNegativeEKgPositiviAlGrammo() {
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), ORIGINE, quote(300, -1, 301)))
                .isInstanceOf(EdizioneNonValida.class).hasMessageContaining("BAR");
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, BigDecimal.ZERO, ORIGINE, QUOTE_600))
                .isInstanceOf(EdizioneNonValida.class);
        assertThatThrownBy(() -> Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("10.8005"), ORIGINE, QUOTE_600))
                .isInstanceOf(EdizioneNonValida.class).hasMessageContaining("al grammo");
        assertThat(Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("10.8"), ORIGINE, quote(300, 150, 90))
                .botanicaDisponibileKg()).isEqualByComparingTo("10.800");
    }

    @Test
    void leQuoteDellEdizioneNonSiModificanoDaFuori() {
        Map<Canale, Integer> quote = new EnumMap<>(QUOTE_600);
        Edizione estate = Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), ORIGINE, quote);

        quote.put(Canale.ONLINE, 0);

        assertThat(estate.bottigliePerCanale()).containsEntry(Canale.ONLINE, 150);
        assertThatThrownBy(() -> estate.quote().put(Canale.ONLINE, Quota.di(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- ricalcolo: la tiratura scende

    @Test
    void laLavandaArrivaScarsa600Diventano540ESociEBarRestanoIntatti() {
        Edizione estate = estate();

        estate.ricalcola(new BigDecimal("10.8"));

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(540));
        assertThat(estate.botanicaDisponibileKg()).isEqualByComparingTo("10.8");
        assertThat(estate.bottigliePerCanale()).containsExactly(
                Map.entry(Canale.SOCI, 300), Map.entry(Canale.BAR, 150), Map.entry(Canale.ONLINE, 90));
        assertThat(estate.eventi()).singleElement().isInstanceOfSatisfying(TiraturaRidotta.class, evento -> {
            assertThat(evento.edizioneId()).isEqualTo(estate.id());
            assertThat(evento.tiraturaVecchia()).isEqualTo(600);
            assertThat(evento.tiraturaNuova()).isEqualTo(540);
            assertThat(evento.quote()).containsExactly(
                    Map.entry(Canale.SOCI, 300), Map.entry(Canale.BAR, 150), Map.entry(Canale.ONLINE, 90));
            assertThat(evento.avvenutaIl()).isNotNull();
        });
    }

    @Test
    void unaRiduzioneGrandeSvuotaOnlineEIntaccaBar() {
        Edizione estate = estate();

        estate.ricalcola(new BigDecimal("7.2"));   // 7200 g / 20 g = 360: ne vanno tolte 240

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(360));
        assertThat(estate.bottigliePerCanale()).containsExactly(
                Map.entry(Canale.SOCI, 300), Map.entry(Canale.BAR, 60), Map.entry(Canale.ONLINE, 0));
    }

    @Test
    void unaRiduzioneCheToccherebbeSociRisponde409ENonCambiaNulla() {
        Edizione estate = estate();

        // 5 kg -> 250 bottiglie: ne vanno tolte 350, ONLINE e BAR insieme ne hanno 300
        assertThatThrownBy(() -> estate.ricalcola(new BigDecimal("5")))
                .isInstanceOf(TiraturaNonRiducibile.class)
                .hasMessageContaining("da 600 a 250")
                .hasMessageContaining("ONLINE e BAR ne hanno in tutto 300")
                .hasMessageContaining("la quota SOCI (300) non si riduce mai");

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
        assertThat(estate.botanicaDisponibileKg()).isEqualByComparingTo("12");
        assertThat(estate.bottigliePerCanale()).isEqualTo(QUOTE_600);
        assertThat(estate.eventi()).isEmpty();
    }

    @Test
    void nessunaQuotaScendeSottoLeBottigliePrenotate() {
        Edizione estate = estate();
        estate.prenota(Canale.ONLINE, 140);
        estate.prenota(Canale.BAR, 140);

        // 10,8 kg: ne vanno tolte 60, ma libere ce ne sono solo 10 + 10
        assertThatThrownBy(() -> estate.ricalcola(new BigDecimal("10.8")))
                .isInstanceOf(TiraturaNonRiducibile.class)
                .hasMessageContaining("ne hanno solo 20 non prenotate")
                .hasMessageContaining("ONLINE 140 prenotate su 150, BAR 140 su 150")
                .hasMessageContaining("sotto le bottiglie già prenotate");

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
        assertThat(estate.quote().get(Canale.ONLINE)).isEqualTo(new Quota(150, 140));
        assertThat(estate.eventi()).isEmpty();
    }

    @Test
    void seOnlineEPrenotatoSiPassaABar() {
        Edizione estate = estate();
        estate.prenota(Canale.ONLINE, 120);

        estate.ricalcola(new BigDecimal("10.8"));   // 60 da togliere: 30 da ONLINE (le libere), 30 da BAR

        assertThat(estate.quote()).containsExactly(
                Map.entry(Canale.SOCI, new Quota(300, 0)),
                Map.entry(Canale.BAR, new Quota(120, 0)),
                Map.entry(Canale.ONLINE, new Quota(120, 120)));
    }

    // ---------------------------------------------------------------- ricalcolo: la tiratura sale o resta uguale

    @Test
    void laTiraturaNonSuperaMai600() {
        Edizione abbondante = Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("20"), ORIGINE, QUOTE_600);
        assertThat(abbondante.tiratura()).isEqualTo(new Tiratura(600));   // 20 kg basterebbero per 1000

        Edizione estate = estate();
        estate.ricalcola(new BigDecimal("10.8"));
        estate.ricalcola(new BigDecimal("50"));

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
        assertThat(estate.bottigliePerCanale().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(600);
    }

    @Test
    void seLaTiraturaSaleLeBottiglieInPiuVannoTutteAOnline() {
        Edizione estate = Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("10.8"), ORIGINE, quote(300, 150, 90));
        estate.eventiPubblicati();   // EdizioneCreata già pubblicato, come dopo il primo save()

        estate.ricalcola(new BigDecimal("12"));

        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
        assertThat(estate.bottigliePerCanale()).containsExactly(
                Map.entry(Canale.SOCI, 300), Map.entry(Canale.BAR, 150), Map.entry(Canale.ONLINE, 150));
        assertThat(estate.eventi()).isEmpty();   // TiraturaRidotta solo quando scende
    }

    @Test
    void conLaStessaTiraturaCambianoSoloIKg() {
        Edizione estate = estate();

        estate.ricalcola(new BigDecimal("13"));   // sempre 600

        assertThat(estate.botanicaDisponibileKg()).isEqualByComparingTo("13");
        assertThat(estate.bottigliePerCanale()).isEqualTo(QUOTE_600);
        assertThat(estate.eventi()).isEmpty();
    }

    @Test
    void conTroppaPocaBotanicaNonSiRiempieNemmenoUnaBottiglia() {
        Edizione estate = estate();

        assertThatThrownBy(() -> estate.ricalcola(new BigDecimal("0.015")))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessageContaining("nemmeno una bottiglia");
        assertThat(estate.tiratura()).isEqualTo(new Tiratura(600));
    }

    // ---------------------------------------------------------------- prenotazioni

    @Test
    void nonSiPrenotanoPiuBottiglieDiQuelleLibere() {
        Edizione estate = estate();
        estate.prenota(Canale.BAR, 100);

        assertThatThrownBy(() -> estate.prenota(Canale.BAR, 51))
                .isInstanceOf(QuotaEsaurita.class)
                .hasMessageContaining("restano 50 bottiglie libere");
        assertThat(estate.quote().get(Canale.BAR)).isEqualTo(new Quota(150, 100));
    }

    // ---------------------------------------------------------------- supporto

    /**
     * L'edizione d'estate di Marco: 12 kg di lavanda a 20 g, 600 bottiglie.
     * Già "salvata": EdizioneCreata è stato pubblicato, restano solo gli eventi nuovi.
     */
    private static Edizione estate() {
        Edizione estate = Edizione.nuova(scelta(2, "20"), 2, new BigDecimal("12"), ORIGINE, QUOTE_600);
        estate.eventiPubblicati();
        return estate;
    }

    private static FormulazioneInCatalogo scelta(int versione, String grammi) {
        return new FormulazioneInCatalogo(FORMULAZIONE, Stato.SCELTA, versione, "Lavanda",
                grammi == null ? null : new BigDecimal(grammi), null);
    }

    private static Map<Canale, Integer> quote(int soci, int bar, int online) {
        Map<Canale, Integer> quote = new EnumMap<>(Canale.class);
        quote.put(Canale.SOCI, soci);
        quote.put(Canale.BAR, bar);
        quote.put(Canale.ONLINE, online);
        return quote;
    }
}
