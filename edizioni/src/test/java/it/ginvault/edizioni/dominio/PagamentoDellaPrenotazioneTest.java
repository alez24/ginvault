package it.ginvault.edizioni.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;

import org.junit.jupiter.api.Test;

/**
 * Test di dominio della SAGA del pagamento, senza Spring e senza Kafka: gli
 * esiti di Pagamenti li "manda" il test chiamando l'edizione.
 *  - la storia di Giulia: dopo 111 bottiglie numerate, le sue sono la 112 e la 113;
 *  - carta rifiutata e tempo finito: annullata, bottiglie di nuovo libere;
 *  - un pagamento che arriva a prenotazione annullata si restituisce;
 *  - ogni esito arrivato due volte non cambia niente.
 */
class PagamentoDellaPrenotazioneTest {

    private static final RegoleDiVendita REGOLE = new RegoleDiVendita(Duration.ofDays(7), 2, 6,
            new BigDecimal("45"), new BigDecimal("52"), Duration.ofMinutes(15));

    private static final Acquirente GIULIA = new Acquirente("github:1001", true);
    private static final String VISA = "pm_card_visa";
    private static final String RIFIUTATA = "La banca ha rifiutato la carta.";

    // ---------------------------------------------------------------- la richiesta di pagamento

    @Test
    void giuliaChiedeDiPagareConLaCartaDiProva() {
        Edizione estate = estate();
        Prenotazione sua = estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl());
        Instant dopoDueMinuti = sua.prenotataIl().plus(Duration.ofMinutes(2));

        sua.richiediPagamento(VISA, "GinVault: 2 bottiglie", dopoDueMinuti);

        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.IN_ATTESA_PAGAMENTO);
        assertThat(sua.metodoPagamento()).isEqualTo(VISA);
        assertThat(sua.pagamentoRichiestoIl()).isEqualTo(dopoDueMinuti);
        assertThat(sua.eventi()).containsExactly(new PagamentoRichiesto(sua.id(), estate.id(), "github:1001", 9000,
                "eur", VISA, "GinVault: 2 bottiglie", dopoDueMinuti));
    }

    @Test
    void ilPagamentoSiChiedeUnaVoltaSolaEPrimaCheIlTempoFinisca() {
        Edizione estate = estate();
        Prenotazione sua = estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl());
        sua.richiediPagamento(VISA, null, sua.prenotataIl());
        sua.eventiPubblicati();

        assertThatThrownBy(() -> sua.richiediPagamento("pm_card_chargeDeclined", null, sua.prenotataIl()))
                .isInstanceOfSatisfying(PagamentoNonPossibile.class, rifiuto -> assertThat(rifiuto.motivo())
                        .isEqualTo(PagamentoNonPossibile.Motivo.PAGAMENTO_GIA_RICHIESTO));

        Prenotazione scaduta = estate.prenota(new Acquirente("github:1002", true), 1, List.of(), REGOLE,
                estate.creataIl());
        assertThatThrownBy(() -> scaduta.richiediPagamento(VISA, null, scaduta.scadeIl()))
                .isInstanceOfSatisfying(PagamentoNonPossibile.class, rifiuto -> {
                    assertThat(rifiuto.motivo()).isEqualTo(PagamentoNonPossibile.Motivo.PRENOTAZIONE_SCADUTA);
                    assertThat(rifiuto.getMessage()).startsWith("Il tempo per pagare è finito alle ");
                });
        assertThat(sua.eventi()).isEmpty();
        assertThat(scaduta.eventi()).isEmpty();
        assertThat(scaduta.pagamentoRichiestoIl()).isNull();
    }

    @Test
    void unaPrenotazioneConfermataOAnnullataNonSiPagaPiu() {
        Edizione estate = estate();
        Prenotazione confermata = estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl());
        estate.pagamentoRiuscito(confermata, UUID.randomUUID(), "pi_1", confermata.prenotataIl());
        Prenotazione annullata = estate.prenota(new Acquirente("github:1002", true), 2, List.of(), REGOLE,
                estate.creataIl());
        estate.pagamentoFallito(annullata, RIFIUTATA, annullata.prenotataIl());

        for (Prenotazione chiusa : List.of(confermata, annullata)) {
            assertThatThrownBy(() -> chiusa.richiediPagamento(VISA, null, chiusa.prenotataIl()))
                    .isInstanceOfSatisfying(PagamentoNonPossibile.class, rifiuto -> assertThat(rifiuto.motivo())
                            .isEqualTo(PagamentoNonPossibile.Motivo.PRENOTAZIONE_CHIUSA));
        }
    }

    // ---------------------------------------------------------------- pagamento riuscito: i numeri

    @Test
    void dopo111BottiglieNumerateQuelleDiGiuliaSonoLa112ELa113() {
        Edizione estate = estate();
        // 55 soci con 2 bottiglie e uno con 1: 111 bottiglie confermate prima di Giulia
        for (int socio = 1; socio <= 56; socio++) {
            Prenotazione altra = estate.prenota(new Acquirente("github:" + socio, true), socio <= 55 ? 2 : 1,
                    List.of(), REGOLE, estate.creataIl());
            estate.pagamentoRiuscito(altra, UUID.randomUUID(), "pi_" + socio, altra.prenotataIl());
        }
        assertThat(estate.bottiglieNumerate()).isEqualTo(111);
        Prenotazione sua = estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl());
        UUID pagamento = UUID.randomUUID();
        Instant adesso = sua.prenotataIl().plusSeconds(150);

        EsitoPrenotazione esito = estate.pagamentoRiuscito(sua, pagamento, "pi_3QxYz", adesso);

        assertThat(esito).isEqualTo(EsitoPrenotazione.CONFERMATA);
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.CONFERMATA);
        assertThat(sua.numeriDiBottiglia()).containsExactly(112, 113);
        assertThat(sua.pagamentoId()).isEqualTo(pagamento);
        assertThat(sua.riferimentoPagamento()).isEqualTo("pi_3QxYz");
        assertThat(sua.confermataIl()).isEqualTo(adesso);
        assertThat(estate.bottiglieNumerate()).isEqualTo(113);
        // Le bottiglie confermate restano tolte dalla quota: sono di chi ha pagato
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isEqualTo(113);
        assertThat(sua.eventi()).containsExactly(new PrenotazioneConfermata(sua.id(), estate.id(), "github:1001",
                Canale.SOCI, 2, List.of(112, 113), new BigDecimal("90.00"), pagamento, adesso));
    }

    @Test
    void leBottiglieDiUnaPrenotazioneAnnullataNonLascianoBuchiNeiNumeri() {
        Edizione estate = estate();
        Prenotazione prima = prenotaPer(estate, "github:1", 2);
        Prenotazione rifiutata = prenotaPer(estate, "github:2", 2);
        Prenotazione terza = prenotaPer(estate, "github:3", 1);

        estate.pagamentoRiuscito(prima, UUID.randomUUID(), "pi_1", prima.prenotataIl());
        estate.pagamentoFallito(rifiutata, RIFIUTATA, rifiutata.prenotataIl());
        estate.pagamentoRiuscito(terza, UUID.randomUUID(), "pi_3", terza.prenotataIl());

        assertThat(prima.numeriDiBottiglia()).containsExactly(1, 2);
        assertThat(rifiutata.numeriDiBottiglia()).isEmpty();
        assertThat(terza.numeriDiBottiglia()).containsExactly(3);
        assertThat(estate.bottiglieNumerate()).isEqualTo(3);
    }

    @Test
    void lostessoPagamentoRiuscitoArrivatoDueVolteNonCambiaNiente() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);
        estate.pagamentoRiuscito(sua, UUID.randomUUID(), "pi_1", sua.prenotataIl());
        sua.eventiPubblicati();

        EsitoPrenotazione esito = estate.pagamentoRiuscito(sua, UUID.randomUUID(), "pi_1", sua.prenotataIl());

        assertThat(esito).isEqualTo(EsitoPrenotazione.GIA_REGISTRATO);
        assertThat(sua.numeriDiBottiglia()).containsExactly(1, 2);
        assertThat(estate.bottiglieNumerate()).isEqualTo(2);
        assertThat(sua.eventi()).isEmpty();
    }

    @Test
    void unPagamentoArrivatoDopoLOraMaPrimaDellaScadenzaConfermaLostesso() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);

        // Il tempo è finito, ma la scadenza non è ancora passata: le bottiglie sono ancora sue
        EsitoPrenotazione esito = estate.pagamentoRiuscito(sua, UUID.randomUUID(), "pi_1",
                sua.scadeIl().plusSeconds(5));

        assertThat(esito).isEqualTo(EsitoPrenotazione.CONFERMATA);
        assertThat(estate.scadenza(sua, sua.scadeIl().plusSeconds(10))).isFalse();
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.CONFERMATA);
    }

    // ---------------------------------------------------------------- compensazione

    @Test
    void conLaCartaRifiutataLaPrenotazioneSiAnnullaELeBottiglieTornanoLibere() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isEqualTo(2);
        Instant adesso = sua.prenotataIl().plusSeconds(90);

        EsitoPrenotazione esito = estate.pagamentoFallito(sua, RIFIUTATA, adesso);

        assertThat(esito).isEqualTo(EsitoPrenotazione.ANNULLATA);
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.ANNULLATA);
        assertThat(sua.causaAnnullamento()).isEqualTo(CausaAnnullamento.PAGAMENTO_RIFIUTATO);
        assertThat(sua.spiegazioneAnnullamento()).isEqualTo(RIFIUTATA);
        assertThat(sua.annullataIl()).isEqualTo(adesso);
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isZero();
        assertThat(sua.eventi()).containsExactly(new PrenotazioneAnnullata(sua.id(), estate.id(), "github:1001",
                Canale.SOCI, 2, CausaAnnullamento.PAGAMENTO_RIFIUTATO, RIFIUTATA, adesso));

        // L'annullata non conta più nel limite per persona: Giulia prenota di nuovo le sue 2 bottiglie
        Prenotazione diNuovo = estate.prenota(GIULIA, 2, List.of(sua), REGOLE, adesso);
        assertThat(diNuovo.bottiglie()).isEqualTo(2);

        sua.eventiPubblicati();
        assertThat(estate.pagamentoFallito(sua, RIFIUTATA, adesso)).isEqualTo(EsitoPrenotazione.GIA_REGISTRATO);
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isEqualTo(2);
        assertThat(sua.eventi()).isEmpty();
    }

    @Test
    void quandoIlTempoFinisceLaPrenotazioneSiAnnulla() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);

        assertThat(estate.scadenza(sua, sua.scadeIl().minusMillis(1))).isFalse();
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.IN_ATTESA_PAGAMENTO);

        assertThat(estate.scadenza(sua, sua.scadeIl())).isTrue();
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.ANNULLATA);
        assertThat(sua.causaAnnullamento()).isEqualTo(CausaAnnullamento.SCADUTA);
        assertThat(sua.spiegazioneAnnullamento()).startsWith("Il tempo per pagare è finito il ")
                .endsWith(": le bottiglie sono tornate disponibili.");
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isZero();

        assertThat(estate.scadenza(sua, sua.scadeIl().plusSeconds(30))).isFalse();
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isZero();
    }

    @Test
    void unaSpiegazioneVuotaDiventaUnaFraseGenerica() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 1);

        estate.pagamentoFallito(sua, "  ", sua.prenotataIl());

        assertThat(sua.spiegazioneAnnullamento()).isEqualTo("Il pagamento non è riuscito.");
    }

    // ---------------------------------------------------------------- il pagamento arrivato tardi

    @Test
    void unPagamentoArrivatoAPrenotazioneScadutaSiRestituisce() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);
        estate.scadenza(sua, sua.scadeIl());
        sua.eventiPubblicati();
        UUID pagamento = UUID.randomUUID();
        Instant tardi = sua.scadeIl().plus(Duration.ofMinutes(10));

        EsitoPrenotazione esito = estate.pagamentoRiuscito(sua, pagamento, "pi_tardi", tardi);

        assertThat(esito).isEqualTo(EsitoPrenotazione.RIMBORSO_RICHIESTO);
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.ANNULLATA);
        assertThat(sua.numeriDiBottiglia()).isEmpty();
        assertThat(sua.rimborsoRichiestoIl()).isEqualTo(tardi);
        assertThat(sua.pagamentoId()).isEqualTo(pagamento);
        assertThat(estate.bottiglieNumerate()).isZero();
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isZero();
        assertThat(sua.eventi()).containsExactly(new RimborsoRichiesto(sua.id(), estate.id(),
                "pagamento arrivato a prenotazione scaduta", tardi));

        // Lo stesso pagamento di nuovo: il rimborso è già chiesto
        sua.eventiPubblicati();
        assertThat(estate.pagamentoRiuscito(sua, pagamento, "pi_tardi", tardi))
                .isEqualTo(EsitoPrenotazione.GIA_REGISTRATO);
        assertThat(sua.eventi()).isEmpty();

        assertThat(sua.rimborsata("re_tardi", tardi.plusSeconds(3))).isEqualTo(EsitoPrenotazione.RIMBORSATA);
        assertThat(sua.riferimentoRimborso()).isEqualTo("re_tardi");
        assertThat(sua.rimborsataIl()).isEqualTo(tardi.plusSeconds(3));
        assertThat(sua.rimborsata("re_tardi", tardi.plusSeconds(9))).isEqualTo(EsitoPrenotazione.GIA_REGISTRATO);
        assertThat(sua.rimborsataIl()).isEqualTo(tardi.plusSeconds(3));
    }

    @Test
    void ancheDopoUnRifiutoUnAddebitoArrivatoSiRestituisce() {
        Edizione estate = estate();
        Prenotazione sua = prenotaPer(estate, "github:1001", 2);
        estate.pagamentoFallito(sua, RIFIUTATA, sua.prenotataIl());
        sua.eventiPubblicati();

        estate.pagamentoRiuscito(sua, UUID.randomUUID(), "pi_1", sua.prenotataIl());

        assertThat(sua.eventi()).singleElement().isInstanceOfSatisfying(RimborsoRichiesto.class, rimborso ->
                assertThat(rimborso.motivo()).isEqualTo("pagamento arrivato a prenotazione annullata"));
    }

    @Test
    void unEdizioneNonTrattaLePrenotazioniDiUnAltra() {
        Edizione estate = estate();
        Edizione autunno = estate();
        Prenotazione inAutunno = prenotaPer(autunno, "github:1001", 2);

        assertThatThrownBy(() -> estate.pagamentoRiuscito(inAutunno, UUID.randomUUID(), "pi_1", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> estate.scadenza(inAutunno, Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(inAutunno.stato()).isEqualTo(StatoPrenotazione.IN_ATTESA_PAGAMENTO);
    }

    // ---------------------------------------------------------------- supporto

    /** L'edizione d'estate: 600 bottiglie (SOCI 300, BAR 150, ONLINE 150), in prelazione. */
    private static Edizione estate() {
        Map<Canale, Integer> quote = new EnumMap<>(Canale.class);
        quote.put(Canale.SOCI, 300);
        quote.put(Canale.BAR, 150);
        quote.put(Canale.ONLINE, 150);
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 2, "Lavanda",
                new BigDecimal("20"), null), 2, new BigDecimal("12"), "Alta Val Varaita (Alpi Cozie)", quote,
                Duration.ofDays(7));
        edizione.eventiPubblicati();
        return edizione;
    }

    private static Prenotazione prenotaPer(Edizione edizione, String socio, int bottiglie) {
        return edizione.prenota(new Acquirente(socio, true), bottiglie, List.of(), REGOLE, edizione.creataIl());
    }
}
