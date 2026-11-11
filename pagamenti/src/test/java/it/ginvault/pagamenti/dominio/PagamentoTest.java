package it.ginvault.pagamenti.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Le regole del pagamento, senza Spring né database. Giulia prenota 2
 * bottiglie a 45,00 euro: 9000 centesimi.
 */
class PagamentoTest {

    private static final Instant ADESSO = Instant.parse("2026-11-11T10:00:00Z");
    private static final UUID PRENOTAZIONE = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");

    private static RichiestaDiPagamento richiesta(String metodo) {
        return new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1234567", 9000, "eur", metodo,
                "GinVault: 2 bottiglie dell'edizione d'estate");
    }

    // ---------------------------------------------------------------- la richiesta

    @Test
    void laRichiestaAccettaUnaCartaDiProvaEPortaLaValutaInMinuscolo() {
        RichiestaDiPagamento richiesta = new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1234567", 9000,
                "EUR", "pm_card_chargeDeclined", null);

        assertThat(richiesta.valuta()).isEqualTo("eur");
        assertThat(richiesta.metodo()).isEqualTo("pm_card_chargeDeclined");
    }

    @Test
    void laRichiestaRifiutaImportiNulliValuteEMetodiStrani() {
        assertThatThrownBy(() -> new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1", 0, "eur",
                "pm_card_visa", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("importo");
        assertThatThrownBy(() -> new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1", 9000, "euro",
                "pm_card_visa", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Valuta");
        assertThatThrownBy(() -> new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1", 9000, "eur",
                "4242 4242 4242 4242", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Metodo");
        assertThatThrownBy(() -> new RichiestaDiPagamento(null, EDIZIONE, "github:1", 9000, "eur",
                "pm_card_visa", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaDescrizioneTroppoLungaSiAccorcia() {
        RichiestaDiPagamento richiesta = new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1", 9000, "eur",
                "pm_card_visa", "x".repeat(500));

        assertThat(richiesta.descrizione()).hasSize(200);
    }

    // ---------------------------------------------------------------- l'addebito

    @Test
    void unAddebitoRiuscitoRaccontaPagamentoRiuscito() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_visa"),
                new EsitoAddebito.Riuscito("pi_giulia"), ADESSO);

        assertThat(pagamento.stato()).isEqualTo(StatoPagamento.RIUSCITO);
        assertThat(pagamento.riferimentoStripe()).isEqualTo("pi_giulia");
        assertThat(pagamento.importoCentesimi()).isEqualTo(9000);
        assertThat(pagamento.metodo()).isEqualTo("pm_card_visa");
        assertThat(pagamento.eventi()).containsExactly(new PagamentoRiuscito(pagamento.id(), PRENOTAZIONE, EDIZIONE,
                "github:1234567", 9000, "eur", "pi_giulia", ADESSO));
    }

    @Test
    void unaCartaRifiutataRaccontaPagamentoFallito() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_chargeDeclined"),
                new EsitoAddebito.Rifiutato(MotivoRifiuto.CARTA_RIFIUTATA, "generic_decline",
                        "La banca ha rifiutato la carta.", "pi_rifiutato"), ADESSO);

        assertThat(pagamento.stato()).isEqualTo(StatoPagamento.RIFIUTATO);
        assertThat(pagamento.motivoRifiuto()).isEqualTo(MotivoRifiuto.CARTA_RIFIUTATA);
        assertThat(pagamento.riferimentoStripe()).isEqualTo("pi_rifiutato");
        assertThat(pagamento.eventi()).containsExactly(new PagamentoFallito(pagamento.id(), PRENOTAZIONE, EDIZIONE,
                "github:1234567", MotivoRifiuto.CARTA_RIFIUTATA, "generic_decline",
                "La banca ha rifiutato la carta.", ADESSO));
    }

    @Test
    void unAddebitoRiuscitoHaSempreIlSuoPaymentIntent() {
        assertThatThrownBy(() -> Pagamento.registra(richiesta("pm_card_visa"), new EsitoAddebito.Riuscito(" "),
                ADESSO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lEsitoSalvatoSiRaccontaDiNuovoUgualeSenzaStripe() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_chargeDeclined"),
                new EsitoAddebito.Rifiutato(MotivoRifiuto.CARTA_RIFIUTATA, "generic_decline", "Rifiutata.", null),
                ADESSO);
        pagamento.eventiPubblicati();

        pagamento.ripetiEsito(ADESSO.plusSeconds(60));

        assertThat(pagamento.esito()).isEqualTo(new EsitoAddebito.Rifiutato(MotivoRifiuto.CARTA_RIFIUTATA,
                "generic_decline", "Rifiutata.", null));
        assertThat(pagamento.eventi()).singleElement().isInstanceOf(PagamentoFallito.class);
    }

    // ---------------------------------------------------------------- il rimborso

    @Test
    void unAddebitoRiuscitoSiRimborsaUnaVolta() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_visa"),
                new EsitoAddebito.Riuscito("pi_giulia"), ADESSO);
        pagamento.eventiPubblicati();

        pagamento.rimborsato("re_giulia", "pagamento arrivato dopo la scadenza", ADESSO.plusSeconds(900));

        assertThat(pagamento.stato()).isEqualTo(StatoPagamento.RIMBORSATO);
        assertThat(pagamento.rimborsoStripe()).isEqualTo("re_giulia");
        assertThat(pagamento.rimborsatoIl()).isEqualTo(ADESSO.plusSeconds(900));
        assertThat(pagamento.eventi()).containsExactly(new RimborsoEseguito(pagamento.id(), PRENOTAZIONE, EDIZIONE,
                "github:1234567", 9000, "eur", "re_giulia", "pagamento arrivato dopo la scadenza",
                ADESSO.plusSeconds(900)));
    }

    @Test
    void ilSecondoRimborsoNonCambiaNienteERaccontaIlPrimo() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_visa"),
                new EsitoAddebito.Riuscito("pi_giulia"), ADESSO);
        pagamento.rimborsato("re_primo", "scaduta", ADESSO.plusSeconds(900));
        pagamento.eventiPubblicati();

        pagamento.rimborsato("re_secondo", "un altro motivo", ADESSO.plusSeconds(1000));

        assertThat(pagamento.rimborsoStripe()).isEqualTo("re_primo");
        assertThat(pagamento.motivoRimborso()).isEqualTo("scaduta");
        assertThat(pagamento.rimborsatoIl()).isEqualTo(ADESSO.plusSeconds(900));
        assertThat(pagamento.eventi()).singleElement()
                .isInstanceOfSatisfying(RimborsoEseguito.class,
                        evento -> assertThat(evento.riferimentoStripe()).isEqualTo("re_primo"));
    }

    @Test
    void unPagamentoRifiutatoNonSiRimborsa() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_chargeDeclined"),
                new EsitoAddebito.Rifiutato(MotivoRifiuto.CARTA_RIFIUTATA, "generic_decline", "Rifiutata.", null),
                ADESSO);

        assertThatThrownBy(pagamento::controllaRimborsabile).isInstanceOf(RimborsoNonPossibile.class)
                .hasMessageContaining("rifiutato");
        assertThatThrownBy(() -> pagamento.rimborsato("re_x", null, ADESSO))
                .isInstanceOf(RimborsoNonPossibile.class);
        assertThat(pagamento.stato()).isEqualTo(StatoPagamento.RIFIUTATO);
    }

    @Test
    void dopoIlRimborsoLAddebitoRestaRiuscito() {
        Pagamento pagamento = Pagamento.registra(richiesta("pm_card_visa"),
                new EsitoAddebito.Riuscito("pi_giulia"), ADESSO);
        pagamento.rimborsato("re_giulia", null, ADESSO);
        pagamento.eventiPubblicati();

        pagamento.ripetiEsito(ADESSO);

        assertThat(pagamento.esito()).isEqualTo(new EsitoAddebito.Riuscito("pi_giulia"));
        assertThat(pagamento.eventi()).singleElement().isInstanceOf(PagamentoRiuscito.class);
    }
}
