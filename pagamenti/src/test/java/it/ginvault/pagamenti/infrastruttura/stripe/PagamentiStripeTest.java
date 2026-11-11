package it.ginvault.pagamenti.infrastruttura.stripe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;

import it.ginvault.pagamenti.dominio.EsitoAddebito;
import it.ginvault.pagamenti.dominio.MotivoRifiuto;
import it.ginvault.pagamenti.dominio.ProviderNonDisponibile;
import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;
import it.ginvault.pagamenti.dominio.RimborsoNonPossibile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PagamentiStripe contro uno Stripe FINTO (WireMock): un server HTTP vero, su
 * una porta casuale, che risponde come l'API di Stripe. Si controlla quello
 * che l'SDK manda (parametri, chiave di idempotenza, chiave segreta) e come
 * si traducono le risposte: riuscito, carta rifiutata, 3D Secure, richiesta
 * non valida, errore 500, chiave sbagliata, Stripe lento.
 *
 * Le risposte sono ridotte ai campi che contano, nel formato di Stripe
 * (https://docs.stripe.com/api/errors).
 */
class PagamentiStripeTest {

    private static final String CHIAVE = "sk_test_finta_per_i_test";
    private static final UUID PRENOTAZIONE = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");

    private static final WireMockServer STRIPE = new WireMockServer(options().dynamicPort());

    private PagamentiStripe provider;

    @BeforeAll
    static void avvia() {
        STRIPE.start();
    }

    @AfterAll
    static void ferma() {
        STRIPE.stop();
    }

    @BeforeEach
    void pulisci() {
        STRIPE.resetAll();
        provider = conChiave(CHIAVE);
    }

    private static PagamentiStripe conChiave(String chiave) {
        return new PagamentiStripe(chiave, STRIPE.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500));
    }

    private static RichiestaDiPagamento richiesta(String metodo) {
        return new RichiestaDiPagamento(PRENOTAZIONE, EDIZIONE, "github:1234567", 9000, "eur", metodo,
                "GinVault: 2 bottiglie dell'edizione d'estate");
    }

    // ---------------------------------------------------------------- addebito

    @Test
    void unaCartaBuonaDiventaUnAddebitoRiuscitoEMandaQuelloCheServe() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(okJson("""
                {"id": "pi_giulia", "object": "payment_intent", "status": "succeeded",
                 "amount": 9000, "currency": "eur"}""")));

        EsitoAddebito esito = provider.addebita(richiesta("pm_card_visa"));

        assertThat(esito).isEqualTo(new EsitoAddebito.Riuscito("pi_giulia"));
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents"))
                .withHeader("Authorization", equalTo("Bearer " + CHIAVE))
                .withHeader("Idempotency-Key", equalTo("ginvault-pagamento-" + PRENOTAZIONE))
                .withFormParam("amount", equalTo("9000"))
                .withFormParam("currency", equalTo("eur"))
                .withFormParam("payment_method", equalTo("pm_card_visa"))
                .withFormParam("confirm", equalTo("true"))
                .withFormParam("automatic_payment_methods[enabled]", equalTo("true"))
                .withFormParam("automatic_payment_methods[allow_redirects]", equalTo("never"))
                .withFormParam("description", equalTo("GinVault: 2 bottiglie dell'edizione d'estate"))
                .withFormParam("metadata[prenotazioneId]", equalTo(PRENOTAZIONE.toString()))
                .withFormParam("metadata[edizioneId]", equalTo(EDIZIONE.toString())));
        // Chi ha prenotato non va a Stripe
        assertThat(STRIPE.getAllServeEvents().getFirst().getRequest().getBodyAsString()).doesNotContain("github");
    }

    @Test
    void unaCartaRifiutataDiventaUnRifiutoConIlDeclineCode() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(402)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "card_error", "code": "card_declined", "decline_code": "generic_decline",
                         "message": "Your card was declined.",
                         "payment_intent": {"id": "pi_rifiutato", "object": "payment_intent",
                                            "status": "requires_payment_method"}}}""")));

        EsitoAddebito esito = provider.addebita(richiesta("pm_card_chargeDeclined"));

        assertThat(esito).isEqualTo(new EsitoAddebito.Rifiutato(MotivoRifiuto.CARTA_RIFIUTATA, "generic_decline",
                "La banca ha rifiutato la carta.", "pi_rifiutato"));
    }

    @Test
    void unaCartaCheChiedeIl3DSecureNonPassaDalServer() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(okJson("""
                {"id": "pi_3ds", "object": "payment_intent", "status": "requires_action"}""")));

        EsitoAddebito esito = provider.addebita(richiesta("pm_card_authenticationRequired"));

        assertThat(esito).isInstanceOfSatisfying(EsitoAddebito.Rifiutato.class, rifiuto -> {
            assertThat(rifiuto.motivo()).isEqualTo(MotivoRifiuto.AUTENTICAZIONE_RICHIESTA);
            assertThat(rifiuto.riferimento()).isEqualTo("pi_3ds");
            assertThat(rifiuto.spiegazione()).contains("3D Secure");
        });
    }

    @Test
    void unMetodoCheNonEsisteDiventaUnaRichiestaNonValida() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "invalid_request_error", "code": "resource_missing",
                         "param": "payment_method",
                         "message": "No such PaymentMethod: 'pm_card_inesistente'"}}""")));

        EsitoAddebito esito = provider.addebita(richiesta("pm_card_inesistente"));

        assertThat(esito).isEqualTo(new EsitoAddebito.Rifiutato(MotivoRifiuto.RICHIESTA_NON_VALIDA,
                "resource_missing", "Stripe non accetta la richiesta di pagamento (resource_missing).", null));
    }

    @Test
    void unErroreDiStripeSiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withHeader("Request-Id", "req_guasto")
                .withBody("""
                        {"error": {"type": "api_error", "message": "Something went wrong on Stripe's end."}}""")));

        assertThatThrownBy(() -> provider.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("ApiException 500")
                .hasMessageContaining("req_guasto")
                .hasMessageContaining(PRENOTAZIONE.toString());
    }

    @Test
    void troppeRichiesteSiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "invalid_request_error", "code": "rate_limit",
                         "message": "Too many requests hit the API too quickly."}}""")));

        assertThatThrownBy(() -> provider.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class);
    }

    @Test
    void unaChiaveSbagliataSiRiprovaSenzaScriverlaNelMessaggio() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "invalid_request_error",
                         "message": "Invalid API Key provided: sk_test_****test"}}""")));

        assertThatThrownBy(() -> provider.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("STRIPE_SECRET_KEY")
                .hasMessageNotContaining("sk_test_")
                .hasNoCause();
    }

    @Test
    void stripeLentoSiInterrompeESiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(okJson("""
                {"id": "pi_lento", "object": "payment_intent", "status": "succeeded"}""").withFixedDelay(2000)));

        long inizio = System.nanoTime();
        assertThatThrownBy(() -> provider.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("non raggiungibile");
        // 500 ms di attesa della risposta e nessun nuovo tentativo dentro l'SDK
        assertThat(Duration.ofNanos(System.nanoTime() - inizio)).isLessThan(Duration.ofMillis(1800));
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/payment_intents")));
    }

    @Test
    void unoStatoCheNonSiAspettaSiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/payment_intents")).willReturn(okJson("""
                {"id": "pi_strano", "object": "payment_intent", "status": "processing"}""")));

        assertThatThrownBy(() -> provider.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("processing");
    }

    // ---------------------------------------------------------------- chiave

    @Test
    void senzaChiaveNonChiamaStripe() {
        PagamentiStripe senzaChiave = conChiave("");

        assertThatThrownBy(() -> senzaChiave.addebita(richiesta("pm_card_visa")))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
        STRIPE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void unaChiaveLiveOPubblicaFaFallireLAvvio() {
        assertThatThrownBy(() -> conChiave("sk_live_" + "x".repeat(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIVE")
                .hasMessageNotContaining("xxxx");
        assertThatThrownBy(() -> conChiave("pk_test_" + "x".repeat(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sk_test_")
                .hasMessageNotContaining("xxxx");
    }

    // ---------------------------------------------------------------- rimborso

    @Test
    void ilRimborsoRestituisceLAddebitoConLaSuaChiaveDiIdempotenza() {
        STRIPE.stubFor(post(urlEqualTo("/v1/refunds")).willReturn(okJson("""
                {"id": "re_giulia", "object": "refund", "status": "succeeded", "amount": 9000,
                 "currency": "eur", "payment_intent": "pi_giulia"}""")));

        String rimborso = provider.rimborsa(PRENOTAZIONE, "pi_giulia");

        assertThat(rimborso).isEqualTo("re_giulia");
        STRIPE.verify(1, postRequestedFor(urlEqualTo("/v1/refunds"))
                .withHeader("Idempotency-Key", equalTo("ginvault-rimborso-" + PRENOTAZIONE))
                .withFormParam("payment_intent", equalTo("pi_giulia"))
                .withFormParam("metadata[prenotazioneId]", equalTo(PRENOTAZIONE.toString())));
    }

    @Test
    void unRimborsoCheStripeNonAccettaNonSiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "invalid_request_error", "code": "charge_already_refunded",
                         "message": "Charge ch_1 has already been refunded."}}""")));

        assertThatThrownBy(() -> provider.rimborsa(PRENOTAZIONE, "pi_giulia"))
                .isInstanceOf(RimborsoNonPossibile.class)
                .hasMessageContaining("charge_already_refunded");
    }

    @Test
    void unRimborsoConStripeGiuSiRiprova() {
        STRIPE.stubFor(post(urlEqualTo("/v1/refunds")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"error": {"type": "api_error", "message": "Service unavailable."}}""")));

        assertThatThrownBy(() -> provider.rimborsa(PRENOTAZIONE, "pi_giulia"))
                .isInstanceOf(ProviderNonDisponibile.class)
                .hasMessageContaining("rimborso");
    }
}
