package it.ginvault.pagamenti.infrastruttura.stripe;

import java.time.Duration;
import java.util.UUID;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCreateParams.AutomaticPaymentMethods;
import com.stripe.param.PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects;
import com.stripe.param.RefundCreateParams;

import it.ginvault.pagamenti.dominio.EsitoAddebito;
import it.ginvault.pagamenti.dominio.MotivoRifiuto;
import it.ginvault.pagamenti.dominio.ProviderDiPagamento;
import it.ginvault.pagamenti.dominio.ProviderNonDisponibile;
import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;
import it.ginvault.pagamenti.dominio.RimborsoNonPossibile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Il provider dei pagamenti con l'SDK ufficiale di Stripe, in MODALITÀ TEST:
 * nessun soldo vero, le carte sono quelle di prova di Stripe.
 *
 * Addebito "lato server": un PaymentIntent creato e confermato in una sola
 * chiamata (confirm = true) con il metodo scelto da Giulia nel menu,
 * pm_card_visa (riesce) o pm_card_chargeDeclined (rifiutata). Niente redirect
 * (allow_redirects = never): una carta che chiede il 3D Secure diventa un
 * rifiuto AUTENTICAZIONE_RICHIESTA.
 *
 * Chiave di idempotenza (header Idempotency-Key) legata alla prenotazione:
 * "ginvault-pagamento-<prenotazione>" e "ginvault-rimborso-<prenotazione>".
 * Se un comando si rielabora, Stripe riconosce la chiave e restituisce la
 * risposta di prima, senza addebitare o rimborsare una seconda volta (Stripe
 * ricorda le chiavi per almeno 24 ore).
 *
 * Come si traducono le risposte:
 *  - PaymentIntent "succeeded"            -> Riuscito
 *  - PaymentIntent "requires_action"      -> Rifiutato AUTENTICAZIONE_RICHIESTA
 *  - 402 card_error (CardException)       -> Rifiutato CARTA_RIFIUTATA, con il decline code
 *  - 400/404 (InvalidRequestException)    -> Rifiutato RICHIESTA_NON_VALIDA
 *  - rete giù, timeout, 5xx, 429, chiave
 *    sbagliata, idempotenza in conflitto  -> ProviderNonDisponibile: si riprova, poi pagamenti.dlt
 *
 * Tempi: 2 s per connettersi e 10 s per la risposta (invece di 30 e 80 di
 * default), e nessun nuovo tentativo dentro l'SDK (maxNetworkRetries = 0): i
 * tentativi li fa ConfigurazioneKafka, e si vedono nei log.
 *
 * La chiave segreta arriva da STRIPE_SECRET_KEY e non si scrive mai nei log.
 * Una chiave LIVE (sk_live_) fa fallire l'avvio.
 */
@Component
public class PagamentiStripe implements ProviderDiPagamento {

    static final String CHIAVE_PAGAMENTO = "ginvault-pagamento-";
    static final String CHIAVE_RIMBORSO = "ginvault-rimborso-";

    private static final Logger LOG = LoggerFactory.getLogger(PagamentiStripe.class);

    /** null se manca la chiave: il servizio parte lo stesso (health, consumer), gli addebiti no. */
    private final StripeClient stripe;

    public PagamentiStripe(@Value("${pagamenti.stripe.chiave:}") String chiave,
                           @Value("${pagamenti.stripe.indirizzo:https://api.stripe.com}") String indirizzo,
                           @Value("${pagamenti.stripe.attesa-connessione:2s}") Duration attesaConnessione,
                           @Value("${pagamenti.stripe.attesa-risposta:10s}") Duration attesaRisposta) {
        String segreta = chiave == null ? "" : chiave.strip();
        if (segreta.isEmpty()) {
            LOG.warn("STRIPE_SECRET_KEY non impostata: Pagamenti parte, ma gli addebiti non passano (si riprovano"
                    + " e poi vanno in pagamenti.dlt) finché la chiave non c'è");
            this.stripe = null;
            return;
        }
        if (segreta.startsWith("sk_live_") || segreta.startsWith("rk_live_")) {
            throw new IllegalStateException("STRIPE_SECRET_KEY è una chiave LIVE: GinVault usa solo la modalità"
                    + " test di Stripe (chiavi sk_test_...)");
        }
        if (!segreta.startsWith("sk_test_") && !segreta.startsWith("rk_test_")) {
            throw new IllegalStateException("STRIPE_SECRET_KEY non è una chiave segreta di test di Stripe: deve"
                    + " iniziare con sk_test_ (o rk_test_ per una chiave con permessi limitati)");
        }
        this.stripe = StripeClient.builder()
                .setApiKey(segreta)
                .setApiBase(indirizzo)
                .setConnectTimeout(Math.toIntExact(attesaConnessione.toMillis()))
                .setReadTimeout(Math.toIntExact(attesaRisposta.toMillis()))
                .setMaxNetworkRetries(0)
                .build();
        LOG.info("Stripe in modalità test, su {}", indirizzo);
    }

    @Override
    public EsitoAddebito addebita(RichiestaDiPagamento richiesta) {
        PaymentIntentCreateParams.Builder parametri = PaymentIntentCreateParams.builder()
                .setAmount(richiesta.importoCentesimi())
                .setCurrency(richiesta.valuta())
                .setPaymentMethod(richiesta.metodo())
                .setConfirm(true)
                .setAutomaticPaymentMethods(AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(AllowRedirects.NEVER)
                        .build())
                // Solo gli id: chi ha prenotato resta in GinVault, non nella dashboard di Stripe
                .putMetadata("prenotazioneId", richiesta.prenotazioneId().toString())
                .putMetadata("edizioneId", richiesta.edizioneId().toString());
        if (richiesta.descrizione() != null && !richiesta.descrizione().isBlank()) {
            parametri.setDescription(richiesta.descrizione());
        }
        try {
            PaymentIntent pagamento = cliente().v1().paymentIntents()
                    .create(parametri.build(), opzioni(CHIAVE_PAGAMENTO + richiesta.prenotazioneId()));
            return switch (pagamento.getStatus()) {
                case "succeeded" -> new EsitoAddebito.Riuscito(pagamento.getId());
                case "requires_action" -> new EsitoAddebito.Rifiutato(MotivoRifiuto.AUTENTICAZIONE_RICHIESTA,
                        "authentication_required", spiegazione("authentication_required"), pagamento.getId());
                default -> throw new ProviderNonDisponibile("Stripe ha risposto con il PaymentIntent "
                        + pagamento.getId() + " nello stato " + pagamento.getStatus()
                        + ", che Pagamenti non sa gestire", null);
            };
        } catch (CardException rifiuto) {
            String codice = rifiuto.getDeclineCode() != null ? rifiuto.getDeclineCode() : rifiuto.getCode();
            MotivoRifiuto motivo = "authentication_required".equals(codice)
                    ? MotivoRifiuto.AUTENTICAZIONE_RICHIESTA : MotivoRifiuto.CARTA_RIFIUTATA;
            return new EsitoAddebito.Rifiutato(motivo, codice, spiegazione(codice), paymentIntent(rifiuto));
        } catch (InvalidRequestException nonValida) {
            LOG.warn("Stripe non accetta l'addebito della prenotazione {} ({}{}): {}", richiesta.prenotazioneId(),
                    nonValida.getCode(), richiestaStripe(nonValida), messaggio(nonValida));
            return new EsitoAddebito.Rifiutato(MotivoRifiuto.RICHIESTA_NON_VALIDA, nonValida.getCode(),
                    "Stripe non accetta la richiesta di pagamento (" + nonValida.getCode() + ").",
                    paymentIntent(nonValida));
        } catch (StripeException errore) {
            throw nonDisponibile("l'addebito della prenotazione " + richiesta.prenotazioneId(), errore);
        }
    }

    @Override
    public String rimborsa(UUID prenotazioneId, String riferimentoPagamento) {
        RefundCreateParams parametri = RefundCreateParams.builder()
                .setPaymentIntent(riferimentoPagamento)
                .putMetadata("prenotazioneId", prenotazioneId.toString())
                .build();
        try {
            Refund rimborso = cliente().v1().refunds().create(parametri, opzioni(CHIAVE_RIMBORSO + prenotazioneId));
            if ("failed".equals(rimborso.getStatus()) || "canceled".equals(rimborso.getStatus())) {
                throw new RimborsoNonPossibile("Stripe non ha eseguito il rimborso " + rimborso.getId()
                        + " della prenotazione " + prenotazioneId + ": stato " + rimborso.getStatus());
            }
            return rimborso.getId();
        } catch (InvalidRequestException nonValida) {
            // Per esempio charge_already_refunded: rimborsato a mano dalla dashboard
            throw new RimborsoNonPossibile("Stripe non accetta il rimborso della prenotazione " + prenotazioneId
                    + " (" + nonValida.getCode() + "): " + messaggio(nonValida));
        } catch (StripeException errore) {
            throw nonDisponibile("il rimborso della prenotazione " + prenotazioneId, errore);
        }
    }

    private StripeClient cliente() {
        if (stripe == null) {
            throw new ProviderNonDisponibile("Manca STRIPE_SECRET_KEY: Pagamenti non può chiamare Stripe", null);
        }
        return stripe;
    }

    private static RequestOptions opzioni(String chiaveDiIdempotenza) {
        return RequestOptions.builder().setIdempotencyKey(chiaveDiIdempotenza).build();
    }

    private static String paymentIntent(StripeException errore) {
        StripeError dettaglio = errore.getStripeError();
        return dettaglio == null || dettaglio.getPaymentIntent() == null ? null : dettaglio.getPaymentIntent().getId();
    }

    /** Il messaggio di Stripe, senza parti della chiave (quello sulla chiave sbagliata ne contiene un pezzo). */
    private static String messaggio(StripeException errore) {
        StripeError dettaglio = errore.getStripeError();
        return dettaglio == null ? errore.getClass().getSimpleName() : dettaglio.getMessage();
    }

    private static ProviderNonDisponibile nonDisponibile(String cosa, StripeException errore) {
        String perche;
        if (errore instanceof AuthenticationException) {
            // Anche PermissionException: una chiave con permessi limitati che non bastano
            perche = "Stripe rifiuta la chiave o i suoi permessi (controlla STRIPE_SECRET_KEY)";
        } else if (errore instanceof ApiConnectionException) {
            perche = "Stripe non raggiungibile (" + errore.getMessage() + ")";
        } else {
            perche = errore.getClass().getSimpleName()
                    + (errore.getStatusCode() == null ? "" : " " + errore.getStatusCode())
                    + (errore.getStripeError() == null ? "" : ": " + errore.getStripeError().getMessage());
        }
        // Senza la causa: il suo messaggio potrebbe ripetere un pezzo della chiave
        return new ProviderNonDisponibile("Stripe non ha dato un esito per " + cosa + ": " + perche
                + richiestaStripe(errore), null);
    }

    /** L'id della richiesta per Stripe (req_...), da cercare nei log della dashboard, se c'è. */
    private static String richiestaStripe(StripeException errore) {
        return errore.getRequestId() == null ? "" : " [richiesta " + errore.getRequestId() + "]";
    }

    /** Il motivo del rifiuto in italiano, per chi legge l'avviso o lo storico. */
    static String spiegazione(String codice) {
        if (codice == null) {
            return "La banca ha rifiutato la carta.";
        }
        return switch (codice) {
            case "generic_decline", "card_declined", "do_not_honor" -> "La banca ha rifiutato la carta.";
            case "insufficient_funds" -> "Sulla carta non ci sono fondi sufficienti.";
            case "lost_card", "stolen_card" -> "La carta risulta smarrita o rubata.";
            case "expired_card" -> "La carta è scaduta.";
            case "incorrect_cvc" -> "Il codice di sicurezza (CVC) non è corretto.";
            case "processing_error" -> "La banca non è riuscita a elaborare il pagamento.";
            case "authentication_required" -> "La carta chiede una verifica (3D Secure) che un pagamento dal"
                    + " server non può completare.";
            default -> "La banca ha rifiutato la carta (" + codice + ").";
        };
    }
}
