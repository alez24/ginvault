package it.ginvault.pagamenti.infrastruttura.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import it.ginvault.pagamenti.dominio.EsitoAddebito;
import it.ginvault.pagamenti.dominio.MotivoRifiuto;
import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * PagamentiStripe contro lo Stripe VERO, in modalità test: nessun soldo vero,
 * ma i PaymentIntent e i rimborsi compaiono nella dashboard di Stripe
 * (Developers / Workbench, oppure Payments con "Test mode" attivo), con
 * metadata prenotazioneId.
 *
 * Parte solo se nell'ambiente c'è STRIPE_SECRET_KEY con una chiave di test
 * (sk_test_ o rk_test_): con "mvn test" normale si salta. Per farlo girare
 * senza aprire il file .env, la chiave la passa Docker Compose, dalla
 * cartella ginvault:
 *
 *   docker compose -f pagamenti/stripe-vero.yml --env-file .env run --rm stripe-vero
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SECRET_KEY", matches = "(sk|rk)_test_.+")
class StripeVeroTest {

    private final PagamentiStripe stripe = new PagamentiStripe(System.getenv("STRIPE_SECRET_KEY"),
            "https://api.stripe.com", Duration.ofSeconds(5), Duration.ofSeconds(20));

    private static RichiestaDiPagamento richiesta(UUID prenotazione, String metodo) {
        return new RichiestaDiPagamento(prenotazione, UUID.randomUUID(), "github:test", 9000, "eur", metodo,
                "GinVault (test automatico): 2 bottiglie dell'edizione d'estate");
    }

    @Test
    void pmCardVisaPassaESiPuoRimborsare() {
        UUID prenotazione = UUID.randomUUID();

        EsitoAddebito esito = stripe.addebita(richiesta(prenotazione, "pm_card_visa"));

        assertThat(esito).isInstanceOfSatisfying(EsitoAddebito.Riuscito.class,
                riuscito -> assertThat(riuscito.riferimento()).startsWith("pi_"));
        String pagamento = ((EsitoAddebito.Riuscito) esito).riferimento();
        String rimborso = stripe.rimborsa(prenotazione, pagamento);
        assertThat(rimborso).startsWith("re_");
        // Stessa chiave di idempotenza: Stripe restituisce lo stesso rimborso, non ne fa un secondo
        assertThat(stripe.rimborsa(prenotazione, pagamento)).isEqualTo(rimborso);
    }

    @Test
    void pmCardChargeDeclinedVieneRifiutata() {
        EsitoAddebito esito = stripe.addebita(richiesta(UUID.randomUUID(), "pm_card_chargeDeclined"));

        assertThat(esito).isInstanceOfSatisfying(EsitoAddebito.Rifiutato.class, rifiuto -> {
            assertThat(rifiuto.motivo()).isEqualTo(MotivoRifiuto.CARTA_RIFIUTATA);
            assertThat(rifiuto.codice()).isEqualTo("generic_decline");
            assertThat(rifiuto.riferimento()).startsWith("pi_");
        });
    }

    @Test
    void laStessaPrenotazioneDueVolteNonAddebitaDueVolte() {
        UUID prenotazione = UUID.randomUUID();
        // La stessa richiesta: con la stessa chiave Stripe vuole anche gli stessi parametri
        RichiestaDiPagamento richiesta = richiesta(prenotazione, "pm_card_visa");

        EsitoAddebito primo = stripe.addebita(richiesta);
        EsitoAddebito secondo = stripe.addebita(richiesta);

        assertThat(primo).isInstanceOf(EsitoAddebito.Riuscito.class);
        // Stripe riconosce la chiave di idempotenza e restituisce lo stesso PaymentIntent
        assertThat(secondo).isEqualTo(primo);
        stripe.rimborsa(prenotazione, ((EsitoAddebito.Riuscito) primo).riferimento());
    }
}
