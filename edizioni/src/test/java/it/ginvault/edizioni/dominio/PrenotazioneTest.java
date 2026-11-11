package it.ginvault.edizioni.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;

import org.junit.jupiter.api.Test;

/**
 * Test di dominio della vendita: prelazione dei soci, vendita online, limite
 * per persona e bottiglie libere. Niente Spring e niente database: l'ora la
 * passa il test, così la prelazione finisce quando serve.
 *
 * La storia: l'edizione d'estate ha 600 bottiglie (SOCI 300, BAR 150, ONLINE
 * 150) e 7 giorni di prelazione; Giulia è socia e prenota 2 bottiglie.
 */
class PrenotazioneTest {

    private static final RegoleDiVendita REGOLE = new RegoleDiVendita(Duration.ofDays(7), 2, 6,
            new BigDecimal("45"), new BigDecimal("52"), Duration.ofMinutes(15));

    private static final Acquirente GIULIA = new Acquirente("github:1001", true);
    private static final Acquirente LUCA = new Acquirente("google:2002", false);

    // ---------------------------------------------------------------- annuncio e stato

    @Test
    void lEdizioneNasceInPrelazionePer7GiorniEConservaLAnnuncio() {
        Edizione estate = estate(quote(300, 150, 150));

        assertThat(estate.prelazioneFinoAl()).isEqualTo(estate.creataIl().plus(Duration.ofDays(7)));
        assertThat(estate.stato(estate.creataIl())).isEqualTo(StatoEdizione.IN_PRELAZIONE);
        assertThat(estate.stato(estate.prelazioneFinoAl().minusSeconds(1))).isEqualTo(StatoEdizione.IN_PRELAZIONE);
        assertThat(estate.stato(estate.prelazioneFinoAl())).isEqualTo(StatoEdizione.IN_VENDITA);
        assertThat(estate.tiraturaAnnunciata()).isEqualTo(600);
        assertThat(estate.botanicaAnnunciataKg()).isEqualByComparingTo("12");
        assertThat(estate.quoteAnnunciate()).isEqualTo(quote(300, 150, 150));
    }

    @Test
    void ilRaccoltoScarsoCambiaLaTiraturaMaNonLAnnuncio() {
        Edizione estate = estate(quote(300, 150, 150));

        estate.ricalcola(new BigDecimal("10.8"));

        assertThat(estate.tiratura().bottiglie()).isEqualTo(540);
        assertThat(estate.tiraturaAnnunciata()).isEqualTo(600);
        assertThat(estate.botanicaAnnunciataKg()).isEqualByComparingTo("12");
        assertThat(estate.quoteAnnunciate()).containsEntry(Canale.ONLINE, 150);
        assertThat(estate.bottigliePerCanale()).containsEntry(Canale.ONLINE, 90);
    }

    @Test
    void conOnlineTuttoPrenotatoDopoLaPrelazioneLEdizioneEEsaurita() {
        Edizione estate = estate(quote(596, 0, 4));
        Instant dopo = estate.prelazioneFinoAl();

        estate.prenota(LUCA, 4, List.of(), REGOLE, dopo);

        assertThat(estate.stato(dopo)).isEqualTo(StatoEdizione.ESAURITA);
        assertThat(estate.stato(estate.creataIl())).isEqualTo(StatoEdizione.IN_PRELAZIONE);
    }

    @Test
    void ilDistillatoreApreLaVenditaPrimaDellaFineDellaPrelazione() {
        Edizione estate = estate(quote(300, 150, 150));
        Instant ilGiornoDopo = estate.creataIl().plus(Duration.ofDays(1));

        estate.apriVendita(ilGiornoDopo);

        assertThat(estate.prelazioneFinoAl()).isEqualTo(ilGiornoDopo);
        assertThat(estate.stato(ilGiornoDopo)).isEqualTo(StatoEdizione.IN_VENDITA);

        // Una seconda volta non sposta niente: la vendita è già aperta
        estate.apriVendita(ilGiornoDopo.plus(Duration.ofDays(1)));
        assertThat(estate.prelazioneFinoAl()).isEqualTo(ilGiornoDopo);
    }

    // ---------------------------------------------------------------- prelazione dei soci

    @Test
    void giuliaPrenota2BottiglieInPrelazioneDallaQuotaDeiSoci() {
        Edizione estate = estate(quote(300, 150, 150));
        Instant adesso = estate.creataIl().plus(Duration.ofDays(1));

        Prenotazione sua = estate.prenota(GIULIA, 2, List.of(), REGOLE, adesso);

        assertThat(sua.edizioneId()).isEqualTo(estate.id());
        assertThat(sua.acquirente()).isEqualTo("github:1001");
        assertThat(sua.canale()).isEqualTo(Canale.SOCI);
        assertThat(sua.bottiglie()).isEqualTo(2);
        assertThat(sua.stato()).isEqualTo(StatoPrenotazione.IN_ATTESA_PAGAMENTO);
        assertThat(sua.prezzoPerBottiglia()).isEqualByComparingTo("45.00");
        assertThat(sua.totale()).isEqualByComparingTo("90.00");
        assertThat(sua.prenotataIl()).isEqualTo(adesso);
        assertThat(sua.scadeIl()).isEqualTo(adesso.plus(Duration.ofMinutes(15)));
        assertThat(estate.quote().get(Canale.SOCI)).isEqualTo(new Quota(300, 2));
        assertThat(estate.quote().get(Canale.ONLINE)).isEqualTo(new Quota(150, 0));
    }

    @Test
    void inPrelazioneChiNonESocioNonPrenotaENienteCambia() {
        Edizione estate = estate(quote(300, 150, 150));

        assertThatThrownBy(() -> estate.prenota(LUCA, 1, List.of(), REGOLE, estate.creataIl()))
                .isInstanceOfSatisfying(PrenotazioneNonPossibile.class, rifiuto -> {
                    assertThat(rifiuto.motivo()).isEqualTo(PrenotazioneNonPossibile.Motivo.PRELAZIONE_DEI_SOCI);
                    assertThat(rifiuto.getMessage()).contains("in prelazione", "solo i soci", "vendita online");
                });
        assertThat(estate.quote().values()).allSatisfy(quota -> assertThat(quota.prenotate()).isZero());
    }

    @Test
    void inPrelazioneUnSocioPrenotaAlMassimo2Bottiglie() {
        Edizione estate = estate(quote(300, 150, 150));
        Instant adesso = estate.creataIl();

        assertThatThrownBy(() -> estate.prenota(GIULIA, 3, List.of(), REGOLE, adesso))
                .isInstanceOfSatisfying(PrenotazioneNonPossibile.class, rifiuto -> {
                    assertThat(rifiuto.motivo()).isEqualTo(PrenotazioneNonPossibile.Motivo.LIMITE_PER_PERSONA);
                    assertThat(rifiuto.getMessage())
                            .isEqualTo("Troppe bottiglie: in prelazione ogni socio prenota al massimo 2 bottiglie"
                                    + " di un'edizione, non 3.");
                });

        List<Prenotazione> sue = new ArrayList<>();
        sue.add(estate.prenota(GIULIA, 1, sue, REGOLE, adesso));
        assertThatThrownBy(() -> estate.prenota(GIULIA, 2, sue, REGOLE, adesso))
                .hasMessage("Hai già 1 bottiglia di questa edizione e in prelazione ogni socio prenota al massimo"
                        + " 2 bottiglie di un'edizione: puoi aggiungerne ancora 1.");
        sue.add(estate.prenota(GIULIA, 1, sue, REGOLE, adesso));
        assertThatThrownBy(() -> estate.prenota(GIULIA, 1, sue, REGOLE, adesso))
                .hasMessageEndingWith(": non puoi aggiungerne altre.");

        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isEqualTo(2);
    }

    @Test
    void leBottiglieDiUnAltraEdizioneNonContanoNelLimite() {
        Edizione estate = estate(quote(300, 150, 150));
        Edizione autunno = estate(quote(300, 150, 150));
        Prenotazione inAutunno = autunno.prenota(GIULIA, 2, List.of(), REGOLE, autunno.creataIl());

        Prenotazione inEstate = estate.prenota(GIULIA, 2, List.of(inAutunno), REGOLE, estate.creataIl());

        assertThat(inEstate.bottiglie()).isEqualTo(2);
    }

    @Test
    void quandoLaQuotaDeiSociEFinitaSiRisponde409ConLeBottiglieLibere() {
        Edizione estate = estate(quote(1, 299, 300));

        assertThatThrownBy(() -> estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl()))
                .isInstanceOf(QuotaEsaurita.class)
                .hasMessage("Nel canale SOCI resta 1 bottiglia libera: non se ne possono prenotare 2");
        assertThat(estate.quote().get(Canale.SOCI).prenotate()).isZero();
    }

    // ---------------------------------------------------------------- vendita online

    @Test
    void dopoLaPrelazioneSociEClientiPrenotanoOnlineAlPrezzoOnline() {
        Edizione estate = estate(quote(300, 150, 150));
        Instant dopo = estate.prelazioneFinoAl().plusSeconds(60);

        Prenotazione diLuca = estate.prenota(LUCA, 6, List.of(), REGOLE, dopo);
        Prenotazione diGiulia = estate.prenota(GIULIA, 1, List.of(), REGOLE, dopo);

        assertThat(diLuca.canale()).isEqualTo(Canale.ONLINE);
        assertThat(diLuca.prezzoPerBottiglia()).isEqualByComparingTo("52.00");
        assertThat(diLuca.totale()).isEqualByComparingTo("312.00");
        assertThat(diGiulia.canale()).isEqualTo(Canale.ONLINE);
        assertThat(estate.quote().get(Canale.ONLINE)).isEqualTo(new Quota(150, 7));
        assertThat(estate.quote().get(Canale.SOCI)).isEqualTo(new Quota(300, 0));
    }

    @Test
    void onlineOgniPersonaPrenotaAlMassimo6BottiglieELePrenotazioniDeiSociNonContano() {
        Edizione estate = estate(quote(300, 150, 150));
        Prenotazione inPrelazione = estate.prenota(GIULIA, 2, List.of(), REGOLE, estate.creataIl());
        Instant dopo = estate.prelazioneFinoAl();

        // Le 2 bottiglie della prelazione sono di un altro canale: online ne restano 6
        Prenotazione online = estate.prenota(GIULIA, 6, List.of(inPrelazione), REGOLE, dopo);

        assertThatThrownBy(() -> estate.prenota(GIULIA, 1, List.of(inPrelazione, online), REGOLE, dopo))
                .isInstanceOfSatisfying(PrenotazioneNonPossibile.class, rifiuto ->
                        assertThat(rifiuto.motivo()).isEqualTo(PrenotazioneNonPossibile.Motivo.LIMITE_PER_PERSONA))
                .hasMessageContaining("online ogni persona prenota al massimo 6 bottiglie");
    }

    @Test
    void nonSiPrenotaMenoDiUnaBottiglia() {
        Edizione estate = estate(quote(300, 150, 150));

        assertThatThrownBy(() -> estate.prenota(GIULIA, 0, List.of(), REGOLE, estate.creataIl()))
                .isInstanceOf(EdizioneNonValida.class)
                .hasMessage("Si prenota almeno una bottiglia");
    }

    @Test
    void conLeBottigliePrenotateOnlineLaTiraturaScendeSoloFinoAlleLibere() {
        Edizione estate = estate(quote(300, 150, 150));
        Instant dopo = estate.prelazioneFinoAl();
        List<Acquirente> clienti = List.of(LUCA, new Acquirente("google:3003", false));
        for (Acquirente cliente : clienti) {
            estate.prenota(cliente, 6, List.of(), REGOLE, dopo);
        }

        // 10,8 kg: -60 bottiglie, tutte da ONLINE (150 -> 90), sopra le 12 prenotate
        estate.ricalcola(new BigDecimal("10.8"));

        assertThat(estate.quote().get(Canale.ONLINE)).isEqualTo(new Quota(90, 12));
    }

    // ---------------------------------------------------------------- supporto

    private static Edizione estate(Map<Canale, Integer> quote) {
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 2, "Lavanda",
                new BigDecimal("20"), null), 2, new BigDecimal("12"), "Alta Val Varaita (Alpi Cozie)", quote,
                Duration.ofDays(7));
        edizione.eventiPubblicati();
        return edizione;
    }

    private static Map<Canale, Integer> quote(int soci, int bar, int online) {
        Map<Canale, Integer> quote = new EnumMap<>(Canale.class);
        quote.put(Canale.SOCI, soci);
        quote.put(Canale.BAR, bar);
        quote.put(Canale.ONLINE, online);
        return quote;
    }
}
