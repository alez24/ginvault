package it.ginvault.edizioni.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.applicazione.EdizioneNonTrovata;
import it.ginvault.edizioni.applicazione.EdizioneVista;
import it.ginvault.edizioni.applicazione.ServizioEdizioni;
import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.CatalogoRicetteNonDisponibile;
import it.ginvault.edizioni.dominio.FormulazioneGiaUsata;
import it.ginvault.edizioni.dominio.FormulazioneNonUtilizzabile;
import it.ginvault.edizioni.dominio.RiferimentoFormulazione;
import it.ginvault.edizioni.dominio.TiraturaNonRiducibile;
import it.ginvault.edizioni.sicurezza.GatewayDiProva;
import it.ginvault.edizioni.sicurezza.SecurityConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Strato web di Edizioni: sicurezza per ruolo (401/403/200) su creazione,
 * ricalcolo e lettura, validazione dei corpi
 * (400) e traduzione degli errori in 404/409/422/503 senza stack trace.
 * Il servizio applicativo è un mock: qui non ci sono né database né Ricette
 * (quelli sono nel test di integrazione con WireMock).
 */
@WebMvcTest(EdizioniController.class)
@Import(SecurityConfig.class)
class EdizioniControllerTest {

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID FORMULAZIONE = UUID.fromString("7f1d2c3e-0000-4000-8000-000000000001");

    private static final String NUOVA_EDIZIONE = """
            {"formulazione": {"id": "7f1d2c3e-0000-4000-8000-000000000001", "versione": 2},
             "botanicaDisponibileKg": 12, "origineBotanica": "Alta Val Varaita (Alpi Cozie)",
             "quote": {"SOCI": 300, "BAR": 150, "ONLINE": 150}}
            """;

    private static final String RICALCOLO = "{\"botanicaDisponibileKg\": 10.8}";

    @DynamicPropertySource
    static void puntaAllaJwksDiProva(DynamicPropertyRegistry registro) {
        registro.add("JWKS_URI", GATEWAY::jwksUri);
    }

    @AfterAll
    static void ferma() {
        GATEWAY.ferma();
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServizioEdizioni servizio;

    // ---------------------------------------------------------------- sicurezza: creazione solo DISTILLATORE

    @Test
    void creazioneSenzaTokenRisponde401() throws Exception {
        mvc.perform(post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void creazioneSenzaIlRuoloDistillatoreRisponde403() throws Exception {
        for (String ruolo : List.of("ADMIN", "SOCIO", "CLIENTE")) {
            mvc.perform(come(ruolo, post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(servizio);   // quindi Ricette non viene nemmeno chiamato
    }

    @Test
    void creazioneComeDistillatoreRisponde201ConLocation() throws Exception {
        when(servizio.crea(eq(FORMULAZIONE), eq(2), any(), eq("Alta Val Varaita (Alpi Cozie)"), anyMap()))
                .thenReturn(vista());

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/edizioni/" + EDIZIONE))
                .andExpect(jsonPath("$.formulazione.id").value(FORMULAZIONE.toString()))
                .andExpect(jsonPath("$.formulazione.versione").value(2))
                .andExpect(jsonPath("$.quote.SOCI").value(300));
    }

    @Test
    void letturaPerDistillatoreEAdminMaNonPerIlSocio() throws Exception {
        when(servizio.trova(EDIZIONE)).thenReturn(vista());

        mvc.perform(come("ADMIN", get("/edizioni/{id}", EDIZIONE))).andExpect(status().isOk());
        mvc.perform(come("DISTILLATORE", get("/edizioni/{id}", EDIZIONE))).andExpect(status().isOk());
        mvc.perform(come("SOCIO", get("/edizioni/{id}", EDIZIONE))).andExpect(status().isForbidden());
        mvc.perform(get("/edizioni/{id}", EDIZIONE)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- elenco: DISTILLATORE e ADMIN

    @Test
    void elencoSenzaTokenRisponde401() throws Exception {
        mvc.perform(get("/edizioni")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void elencoComeSocioOClienteRisponde403() throws Exception {
        for (String ruolo : List.of("SOCIO", "CLIENTE")) {
            mvc.perform(come(ruolo, get("/edizioni"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(servizio);
    }

    @Test
    void elencoComeDistillatoreOAdminRisponde200() throws Exception {
        when(servizio.elenco()).thenReturn(List.of(vista("10.800", 540, 90), vista()));

        for (String ruolo : List.of("DISTILLATORE", "ADMIN")) {
            mvc.perform(come(ruolo, get("/edizioni")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].tiratura").value(540))
                    .andExpect(jsonPath("$[0].quote.ONLINE").value(90))
                    .andExpect(jsonPath("$[1].tiratura").value(600));
        }
    }

    // ---------------------------------------------------------------- ricalcolo: solo DISTILLATORE

    @Test
    void ricalcoloSenzaTokenRisponde401() throws Exception {
        mvc.perform(post("/edizioni/{id}/ricalcolo", EDIZIONE).contentType(MediaType.APPLICATION_JSON).content(RICALCOLO))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void ricalcoloSenzaIlRuoloDistillatoreRisponde403() throws Exception {
        for (String ruolo : List.of("ADMIN", "SOCIO", "CLIENTE")) {
            mvc.perform(come(ruolo, post("/edizioni/{id}/ricalcolo", EDIZIONE)
                            .contentType(MediaType.APPLICATION_JSON).content(RICALCOLO)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(servizio);
    }

    @Test
    void ricalcoloComeDistillatoreRisponde200ConLaNuovaTiratura() throws Exception {
        when(servizio.ricalcola(eq(EDIZIONE), eq(new BigDecimal("10.8")))).thenReturn(vista("10.800", 540, 90));

        mvc.perform(come("DISTILLATORE", post("/edizioni/{id}/ricalcolo", EDIZIONE)
                        .contentType(MediaType.APPLICATION_JSON).content(RICALCOLO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiratura").value(540))
                .andExpect(jsonPath("$.quote.SOCI").value(300))
                .andExpect(jsonPath("$.quote.BAR").value(150))
                .andExpect(jsonPath("$.quote.ONLINE").value(90))
                .andExpect(jsonPath("$.prenotate.ONLINE").value(0));
    }

    @Test
    void ricalcoloImpossibileRisponde409ConLaSpiegazione() throws Exception {
        when(servizio.ricalcola(any(), any())).thenThrow(new TiraturaNonRiducibile(
                "Con 5 kg di lavanda la tiratura scenderebbe da 600 a 250 bottiglie: la quota SOCI (300) non si riduce mai."));

        mvc.perform(come("DISTILLATORE", post("/edizioni/{id}/ricalcolo", EDIZIONE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"botanicaDisponibileKg\": 5}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Tiratura non riducibile"))
                .andExpect(jsonPath("$.detail").value(containsString("SOCI (300) non si riduce mai")));
    }

    @Test
    void ricalcoloConKgNonValidiRisponde400EDiUnEdizioneInesistente404() throws Exception {
        mvc.perform(come("DISTILLATORE", post("/edizioni/{id}/ricalcolo", EDIZIONE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"botanicaDisponibileKg\": -1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campi[?(@.campo == 'botanicaDisponibileKg')]").exists());
        verifyNoInteractions(servizio);

        when(servizio.ricalcola(any(), any())).thenThrow(new EdizioneNonTrovata(EDIZIONE));
        mvc.perform(come("DISTILLATORE", post("/edizioni/{id}/ricalcolo", EDIZIONE)
                        .contentType(MediaType.APPLICATION_JSON).content(RICALCOLO)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- validazione: 400

    @Test
    void corpoNonValidoRisponde400ConICampiSbagliati() throws Exception {
        String sbagliato = """
                {"formulazione": {"versione": 0}, "botanicaDisponibileKg": -1, "quote": {"SOCI": -5}}
                """;

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(sbagliato)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Richiesta non valida"))
                .andExpect(jsonPath("$.campi[?(@.campo == 'formulazione.id')]").exists())
                .andExpect(jsonPath("$.campi[?(@.campo == 'formulazione.versione')]").exists())
                .andExpect(jsonPath("$.campi[?(@.campo == 'botanicaDisponibileKg')]").exists())
                .andExpect(jsonPath("$.campi[?(@.campo == 'origineBotanica')]").exists())
                .andExpect(jsonPath("$.campi[?(@.campo =~ /quote.*SOCI.*/)]").exists());
        verifyNoInteractions(servizio);
    }

    @Test
    void canaleSconosciutoRisponde400() throws Exception {
        String negozio = NUOVA_EDIZIONE.replace("\"ONLINE\"", "\"NEGOZIO\"");

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(negozio)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servizio);
    }

    // ---------------------------------------------------------------- errori di dominio e di Ricette

    @Test
    void formulazioneNonUtilizzabileRisponde422() throws Exception {
        when(servizio.crea(any(), anyInt(), any(), any(), anyMap()))
                .thenThrow(new FormulazioneNonUtilizzabile("La formulazione X non è ancora stata scelta dal panel"));

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Formulazione non utilizzabile"))
                .andExpect(jsonPath("$.detail").value(containsString("non è ancora stata scelta")));
    }

    @Test
    void formulazioneGiaUsataRisponde409() throws Exception {
        when(servizio.crea(any(), anyInt(), any(), any(), anyMap()))
                .thenThrow(new FormulazioneGiaUsata("una formulazione usata non si riusa mai"));

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Formulazione già usata"));
    }

    @Test
    void ricetteNonDisponibileRisponde503ConMessaggioChiaroESenzaStackTrace() throws Exception {
        when(servizio.crea(any(), anyInt(), any(), any(), anyMap()))
                .thenThrow(new CatalogoRicetteNonDisponibile("Ricette non risponde (spento, irraggiungibile o più lento di 2 secondi)"));

        mvc.perform(come("DISTILLATORE", post("/edizioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_EDIZIONE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.title").value("Ricette non disponibile"))
                .andExpect(jsonPath("$.detail").value("Ricette non risponde (spento, irraggiungibile o più lento di 2 secondi)"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void edizioneInesistenteRisponde404() throws Exception {
        when(servizio.trova(EDIZIONE)).thenThrow(new EdizioneNonTrovata(EDIZIONE));

        mvc.perform(come("ADMIN", get("/edizioni/{id}", EDIZIONE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Edizione " + EDIZIONE + " non trovata"));
    }

    // ---------------------------------------------------------------- supporto

    private static MockHttpServletRequestBuilder come(String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token(ruolo));
    }

    private static EdizioneVista vista() {
        return vista("12.000", 600, 150);
    }

    private static EdizioneVista vista(String kg, int tiratura, int online) {
        return new EdizioneVista(EDIZIONE, new RiferimentoFormulazione(FORMULAZIONE, 2), "lavanda",
                new BigDecimal("20.00"), new BigDecimal(kg), "Alta Val Varaita (Alpi Cozie)", tiratura,
                Map.of(Canale.SOCI, 300, Canale.BAR, 150, Canale.ONLINE, online),
                Map.of(Canale.SOCI, 0, Canale.BAR, 0, Canale.ONLINE, 0), Instant.now());
    }
}
