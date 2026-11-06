package it.ginvault.edizioni.infrastruttura.ricette;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import it.ginvault.edizioni.dominio.CatalogoRicette;
import it.ginvault.edizioni.dominio.CatalogoRicetteNonDisponibile;
import it.ginvault.edizioni.dominio.FormulazioneGiaUsata;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.FormulazioneNonUtilizzabile;
import it.ginvault.edizioni.dominio.RiferimentoFormulazione;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter REST di CatalogoRicette: l'ANTI-CORRUPTION LAYER verso Ricette.
 *
 * Solo questa classe conosce il "linguaggio" di Ricette: gli URL
 * (/formulazioni/{id}, /formulazioni/{id}/utilizzo), il JSON di
 * FormulazioneVista, gli stati IN_PROVA/SCELTA/USATA e i codici HTTP. Tutto
 * viene tradotto nel modello di Edizioni (FormulazioneInCatalogo) e nelle sue
 * eccezioni di dominio, così nessun dettaglio di Ricette entra nel dominio.
 *
 * Chiamata diretta servizio -> servizio sulla rete interna (non passa dal
 * gateway), con:
 *  - TIMEOUT di 2 secondi sia per aprire la connessione sia per la risposta:
 *    se Ricette è lento, Marco riceve un 503 invece di restare appeso;
 *  - TOKEN RELAY: inoltra lo stesso JWT dell'utente che ha chiamato Edizioni,
 *    quindi Ricette applica le SUE regole sui ruoli (DISTILLATORE).
 *
 * CIRCUIT BREAKER: qui non c'è, arriverà con Istio. Il sidecar Envoy davanti a
 * Ricette applicherà timeout, retry e "outlier detection" (smette di mandare
 * richieste a un'istanza che continua a fallire) come configurazione della
 * mesh, senza librerie nel codice. Per ora ogni richiesta prova Ricette e, al
 * peggio, aspetta 2 secondi.
 */
@Component
public class CatalogoRicetteRest implements CatalogoRicette {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogoRicetteRest.class);

    private final RestClient ricette;
    private final long secondi;

    public CatalogoRicetteRest(RestClient.Builder builder,
                               @Value("${ricette.url}") String indirizzo,
                               @Value("${ricette.timeout}") Duration timeout) {
        // Client HTTP del JDK: timeout di connessione qui, timeout di risposta
        // sulla factory. HTTP/1.1 esplicito: niente tentativo di upgrade a HTTP/2.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory fabbrica = new JdkClientHttpRequestFactory(http);
        fabbrica.setReadTimeout(timeout);

        // Il builder di Spring Boot porta già i convertitori JSON configurati
        // (e in futuro il tracing): lo completiamo con indirizzo, timeout e token.
        this.ricette = builder
                .baseUrl(indirizzo)
                .requestFactory(fabbrica)
                .requestInterceptor(CatalogoRicetteRest::inoltraIlJwtDellUtente)
                .build();
        this.secondi = timeout.toSeconds();
    }

    @Override
    public Optional<FormulazioneInCatalogo> trova(UUID formulazioneId) {
        FormulazioneDiRicette letta;
        try {
            letta = ricette.get()
                    .uri("/formulazioni/{id}", formulazioneId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(FormulazioneDiRicette.class);
        } catch (HttpClientErrorException.NotFound inesistente) {
            return Optional.empty();
        } catch (ResourceAccessException irraggiungibile) {
            // connessione rifiutata, nome sconosciuto, timeout di connessione o di risposta
            throw nonDisponibile("Ricette non risponde (spento, irraggiungibile o più lento di " + secondi
                    + " secondi): l'edizione non è stata creata. Riprova tra poco.",
                    irraggiungibile.toString());
        } catch (RestClientException inattesa) {
            // 5xx, 401/403 (configurazione sbagliata), JSON illeggibile...
            throw rispostaInattesa("alla lettura della formulazione " + formulazioneId, inattesa.toString());
        }
        return Optional.of(traduci(letta, formulazioneId));
    }

    @Override
    public void segnaUsata(RiferimentoFormulazione formulazione, UUID edizioneId) {
        try {
            ricette.post()
                    .uri("/formulazioni/{id}/utilizzo", formulazione.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("edizioneId", edizioneId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict usata) {
            // Un'altra edizione l'ha presa tra la nostra GET e questa POST
            throw new FormulazioneGiaUsata("La formulazione " + formulazione.id()
                    + " è già stata usata da un'altra edizione: una formulazione usata non si riusa mai");
        } catch (HttpClientErrorException.NotFound sparita) {
            throw new FormulazioneNonUtilizzabile("La formulazione " + formulazione.id() + " non esiste più in Ricette");
        } catch (ResourceAccessException irraggiungibile) {
            // Esito incerto: Ricette potrebbe averla segnata USATA e non aver
            // fatto in tempo a rispondere. È il limite delle chiamate sincrone
            // non transazionali (vedi ServizioEdizioni.crea).
            throw nonDisponibile("Ricette non ha confermato l'utilizzo della formulazione entro " + secondi
                    + " secondi: l'edizione non è stata creata. Riprova tra poco.", irraggiungibile.toString());
        } catch (RestClientException inattesa) {
            throw rispostaInattesa("all'utilizzo della formulazione " + formulazione.id(), inattesa.toString());
        }
    }

    // ---------------------------------------------------------------- traduzione

    /** Dal modello di Ricette a quello di Edizioni. */
    private static FormulazioneInCatalogo traduci(FormulazioneDiRicette f, UUID richiesta) {
        if (f == null || f.stato() == null || !richiesta.equals(f.id())) {
            throw rispostaInattesa("alla lettura della formulazione " + richiesta, "corpo vuoto o incompleto");
        }
        FormulazioneInCatalogo.Stato stato = switch (f.stato()) {
            case "IN_PROVA" -> FormulazioneInCatalogo.Stato.DA_SCEGLIERE;
            case "SCELTA" -> FormulazioneInCatalogo.Stato.SCELTA;
            case "USATA" -> FormulazioneInCatalogo.Stato.USATA;
            default -> throw rispostaInattesa("alla lettura della formulazione " + richiesta,
                    "stato sconosciuto " + f.stato());
        };
        return new FormulazioneInCatalogo(f.id(), stato, f.versioneScelta(), f.botanicaDiStagione(),
                grammiDellaBotanicaDiStagione(f), f.edizioneId());
    }

    /** Grammi per bottiglia della botanica di stagione nella versione scelta (null se non c'è). */
    private static BigDecimal grammiDellaBotanicaDiStagione(FormulazioneDiRicette f) {
        if (f.versioneScelta() == null || f.botanicaDiStagione() == null || f.prove() == null) {
            return null;
        }
        String stagione = f.botanicaDiStagione().trim().toLowerCase(Locale.ROOT);
        return f.prove().stream()
                .filter(prova -> prova.versione() == f.versioneScelta())
                .flatMap(prova -> prova.dosi() == null ? Stream.<DoseDiRicette>empty() : prova.dosi().stream())
                .filter(dose -> dose.botanica() != null && dose.botanica().trim().toLowerCase(Locale.ROOT).equals(stagione))
                .map(DoseDiRicette::grammiPerBottiglia)
                .findFirst()
                .orElse(null);
    }

    private static CatalogoRicetteNonDisponibile rispostaInattesa(String dove, String causa) {
        return nonDisponibile("Ricette ha risposto in modo inatteso " + dove
                + ": l'edizione non è stata creata. Riprova tra poco.", causa);
    }

    private static CatalogoRicetteNonDisponibile nonDisponibile(String messaggio, String causa) {
        // Nei log la causa tecnica (una riga, senza stack trace); al client solo il messaggio
        LOG.warn("{} Causa: {}", messaggio, causa);
        return new CatalogoRicetteNonDisponibile(messaggio);
    }

    /** Token relay: lo stesso "Authorization: Bearer <jwt>" arrivato a Edizioni dal gateway. */
    private static ClientHttpResponse inoltraIlJwtDellUtente(HttpRequest richiesta, byte[] corpo,
                                                             ClientHttpRequestExecution esecuzione)
            throws IOException {
        Authentication utente = SecurityContextHolder.getContext().getAuthentication();
        if (utente instanceof JwtAuthenticationToken jwt) {
            richiesta.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
        }
        return esecuzione.execute(richiesta, corpo);
    }

    // ---------------------------------------------------------------- JSON di Ricette

    /*
     * Il JSON di FormulazioneVista di Ricette, ridotto ai campi che servono qui
     * ("tolerant reader": i campi in più, come nome o registrataIl, si ignorano).
     * Lo stato è una stringa, non un enum: uno stato nuovo in Ricette non deve
     * rompere la lettura del JSON, se ne occupa traduci().
     */
    record FormulazioneDiRicette(UUID id, String botanicaDiStagione, String stato, Integer versioneScelta,
                                 UUID edizioneId, List<ProvaDiRicette> prove) {
    }

    record ProvaDiRicette(int versione, List<DoseDiRicette> dosi) {
    }

    record DoseDiRicette(String botanica, BigDecimal grammiPerBottiglia) {
    }
}
