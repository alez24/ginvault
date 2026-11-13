package it.ginvault.edizioni.infrastruttura.ricette;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import it.ginvault.edizioni.dominio.CatalogoRicetteNonDisponibile;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.RiferimentoFormulazione;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

/**
 * L'adapter da solo, senza Spring: traduzione del JSON di Ricette nel modello
 * di Edizioni, token relay e cosa succede quando Ricette è spento, è fermo
 * dietro un proxy (502, 503, 504) o risponde in modo inatteso.
 */
class CatalogoRicetteRestTest {

    private static final WireMockServer RICETTE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final UUID ID = UUID.fromString("7f1d2c3e-0000-4000-8000-000000000001");

    @BeforeAll
    static void avvia() {
        RICETTE.start();
    }

    @AfterAll
    static void ferma() {
        RICETTE.stop();
    }

    @AfterEach
    void pulisci() {
        RICETTE.resetAll();
        SecurityContextHolder.clearContext();
    }

    @Test
    void traduceIlJsonDiRicetteEInoltraIlJwt() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("jwt-di-marco").header("alg", "RS256").subject("github:42").build()));
        // Campi in più (nome, esito, nuovoCampo) ignorati; botanica scritta in modo diverso
        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + ID)).willReturn(okJson("""
                {"id": "%s", "nome": "Edizione d'estate", "botanicaDiStagione": "Lavanda",
                 "stato": "SCELTA", "versioneScelta": 2, "edizioneId": null, "nuovoCampo": true,
                 "prove": [
                   {"versione": 1, "esito": "ARCHIVIATA", "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 18.00}]},
                   {"versione": 2, "esito": "SCELTA", "dosi": [{"botanica": "ginepro", "grammiPerBottiglia": 10.00},
                                                               {"botanica": " lavanda ", "grammiPerBottiglia": 20.00}]}]}
                """.formatted(ID))));

        FormulazioneInCatalogo letta = adapterVerso(RICETTE.baseUrl()).trova(ID).orElseThrow();

        assertThat(letta.stato()).isEqualTo(FormulazioneInCatalogo.Stato.SCELTA);
        assertThat(letta.versioneScelta()).isEqualTo(2);
        assertThat(letta.grammiPerBottiglia()).isEqualByComparingTo("20");   // della versione 2, non della 1
        RICETTE.verify(getRequestedFor(urlEqualTo("/formulazioni/" + ID))
                .withHeader("Authorization", equalTo("Bearer jwt-di-marco")));
    }

    @Test
    void inProvaDiventaDaScegliere() {
        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + ID)).willReturn(okJson("""
                {"id": "%s", "botanicaDiStagione": "lavanda", "stato": "IN_PROVA", "versioneScelta": null,
                 "prove": [{"versione": 1, "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 18.00}]}]}
                """.formatted(ID))));

        FormulazioneInCatalogo letta = adapterVerso(RICETTE.baseUrl()).trova(ID).orElseThrow();

        assertThat(letta.stato()).isEqualTo(FormulazioneInCatalogo.Stato.DA_SCEGLIERE);
        assertThat(letta.grammiPerBottiglia()).isNull();
    }

    @Test
    void ricetteSpentoDiventaCatalogoNonDisponibile() throws Exception {
        int portaChiusa;
        try (ServerSocket socket = new ServerSocket(0)) {
            portaChiusa = socket.getLocalPort();
        }   // chiuso: nessuno ascolta più su quella porta

        assertThatThrownBy(() -> adapterVerso("http://127.0.0.1:" + portaChiusa).trova(ID))
                .isInstanceOf(CatalogoRicetteNonDisponibile.class)
                .hasMessageContaining("spento, irraggiungibile")
                .hasMessageContaining("l'edizione non è stata creata");
    }

    @Test
    void risposteInatteseDiventanoCatalogoNonDisponibile() {
        CatalogoRicetteRest adapter = adapterVerso(RICETTE.baseUrl());

        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + ID)).willReturn(serverError()));
        assertThatThrownBy(() -> adapter.trova(ID))
                .isInstanceOf(CatalogoRicetteNonDisponibile.class).hasMessageContaining("in modo inatteso");

        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + ID)).willReturn(okJson("""
                {"id": "%s", "botanicaDiStagione": "lavanda", "stato": "SOSPESA", "prove": []}
                """.formatted(ID))));
        assertThatThrownBy(() -> adapter.trova(ID))
                .isInstanceOf(CatalogoRicetteNonDisponibile.class).hasMessageContaining("in modo inatteso");
    }

    /** Le risposte del sidecar di Istio: "no healthy upstream" (503), "upstream request timeout" (504). */
    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void ricetteFermoDietroIlProxyDiventaNonDisponibile(int codice) {
        CatalogoRicetteRest adapter = adapterVerso(RICETTE.baseUrl());
        RICETTE.stubFor(get(urlEqualTo("/formulazioni/" + ID))
                .willReturn(aResponse().withStatus(codice).withBody("no healthy upstream")));
        RICETTE.stubFor(post(urlEqualTo("/formulazioni/" + ID + "/utilizzo"))
                .willReturn(aResponse().withStatus(codice).withBody("upstream request timeout")));

        assertThatThrownBy(() -> adapter.trova(ID))
                .isInstanceOf(CatalogoRicetteNonDisponibile.class)
                .hasMessage("Ricette non è disponibile in questo momento: l'edizione non è stata creata."
                        + " Riprova tra poco.");
        assertThatThrownBy(() -> adapter.segnaUsata(new RiferimentoFormulazione(ID, 1), UUID.randomUUID()))
                .isInstanceOf(CatalogoRicetteNonDisponibile.class)
                .hasMessageStartingWith("Ricette non è disponibile e non ha confermato l'utilizzo")
                .hasMessageContaining("l'edizione non è stata creata");
    }

    private static CatalogoRicetteRest adapterVerso(String indirizzo) {
        return new CatalogoRicetteRest(RestClient.builder(), indirizzo, Duration.ofSeconds(2));
    }
}
