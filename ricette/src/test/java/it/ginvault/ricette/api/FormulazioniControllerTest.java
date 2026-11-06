package it.ginvault.ricette.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.UUID;

import it.ginvault.ricette.applicazione.FormulazioneNonTrovata;
import it.ginvault.ricette.applicazione.FormulazioneVista;
import it.ginvault.ricette.applicazione.ServizioFormulazioni;
import it.ginvault.ricette.dominio.Dose;
import it.ginvault.ricette.dominio.EsitoProva;
import it.ginvault.ricette.dominio.FormulazioneNonValida;
import it.ginvault.ricette.dominio.StatoFormulazione;
import it.ginvault.ricette.dominio.TransizioneNonConsentita;
import it.ginvault.ricette.sicurezza.GatewayDiProva;
import it.ginvault.ricette.sicurezza.SecurityConfig;

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
 * Strato web di Ricette: sicurezza per ruolo (401/403/200), validazione dei
 * corpi (400) e traduzione degli errori di dominio (404/409/422).
 * Il servizio applicativo è un mock: qui non c'è database.
 */
@WebMvcTest(FormulazioniController.class)
@Import(SecurityConfig.class)
class FormulazioniControllerTest {

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final UUID ID = UUID.fromString("7f1d2c3e-0000-4000-8000-000000000001");

    private static final String NUOVA_FORMULAZIONE = """
            {"nome": "Edizione d'estate", "botanicaDiStagione": "lavanda",
             "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 18},
                      {"botanica": "ginepro",  "grammiPerBottiglia": 12}]}
            """;

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
    private ServizioFormulazioni servizio;

    // ---------------------------------------------------------------- lettura: DISTILLATORE e ADMIN

    @Test
    void letturaSenzaTokenRisponde401() throws Exception {
        mvc.perform(get("/formulazioni/{id}", ID)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void letturaComeClienteRisponde403() throws Exception {
        mvc.perform(come("CLIENTE", get("/formulazioni/{id}", ID))).andExpect(status().isForbidden());
        verifyNoInteractions(servizio);
    }

    @Test
    void letturaComeDistillatoreOAdminRisponde200() throws Exception {
        when(servizio.trova(ID)).thenReturn(vista(StatoFormulazione.SCELTA, 2));

        for (String ruolo : List.of("DISTILLATORE", "ADMIN")) {
            mvc.perform(come(ruolo, get("/formulazioni/{id}", ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stato").value("SCELTA"))
                    .andExpect(jsonPath("$.versioneScelta").value(2))
                    .andExpect(jsonPath("$.prove[1].esito").value("SCELTA"))
                    .andExpect(jsonPath("$.prove[1].dosi[0].botanica").value("lavanda"))
                    .andExpect(jsonPath("$.prove[1].dosi[0].grammiPerBottiglia").value(20.00));
        }
    }

    // ---------------------------------------------------------------- scrittura: solo DISTILLATORE

    @Test
    void creazioneComeAdminRisponde403() throws Exception {
        mvc.perform(come("ADMIN", post("/formulazioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_FORMULAZIONE)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(servizio);
    }

    @Test
    void creazioneSenzaTokenRisponde401() throws Exception {
        mvc.perform(post("/formulazioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_FORMULAZIONE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creazioneComeDistillatoreRisponde201ConLocation() throws Exception {
        when(servizio.crea(eq("Edizione d'estate"), eq("lavanda"), anyList())).thenReturn(vista(StatoFormulazione.IN_PROVA, null));

        mvc.perform(come("DISTILLATORE", post("/formulazioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_FORMULAZIONE)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/formulazioni/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.stato").value("IN_PROVA"));
    }

    @Test
    void sceltaEUtilizzoSonoSoloPerIlDistillatore() throws Exception {
        mvc.perform(come("ADMIN", post("/formulazioni/{id}/scelta", ID).contentType(MediaType.APPLICATION_JSON).content("{\"versione\": 2}")))
                .andExpect(status().isForbidden());
        mvc.perform(come("SOCIO", post("/formulazioni/{id}/versioni", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dosi\": [{\"botanica\": \"lavanda\", \"grammiPerBottiglia\": 20}]}")))
                .andExpect(status().isForbidden());
        mvc.perform(come("ADMIN", post("/formulazioni/{id}/utilizzo", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"edizioneId\": \"" + UUID.randomUUID() + "\"}")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(servizio);
    }

    // ---------------------------------------------------------------- validazione: 400

    @Test
    void corpoNonValidoRisponde400ConICampiSbagliati() throws Exception {
        String sbagliato = """
                {"nome": "", "botanicaDiStagione": "lavanda",
                 "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": -5}]}
                """;

        mvc.perform(come("DISTILLATORE", post("/formulazioni").contentType(MediaType.APPLICATION_JSON).content(sbagliato)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Richiesta non valida"))
                .andExpect(jsonPath("$.campi[?(@.campo == 'nome')]").exists())
                .andExpect(jsonPath("$.campi[?(@.campo == 'dosi[0].grammiPerBottiglia')]").exists());
        verifyNoInteractions(servizio);
    }

    @Test
    void idMalformatoRisponde400() throws Exception {
        mvc.perform(come("DISTILLATORE", get("/formulazioni/non-un-uuid"))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- errori di dominio

    @Test
    void formulazioneInesistenteRisponde404() throws Exception {
        when(servizio.trova(ID)).thenThrow(new FormulazioneNonTrovata(ID));

        mvc.perform(come("DISTILLATORE", get("/formulazioni/{id}", ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Formulazione " + ID + " non trovata"));
    }

    @Test
    void transizioneNonConsentitaRisponde409() throws Exception {
        when(servizio.scegli(any(), anyInt())).thenThrow(new TransizioneNonConsentita("Il panel ha già scelto la versione 2"));

        mvc.perform(come("DISTILLATORE", post("/formulazioni/{id}/scelta", ID).contentType(MediaType.APPLICATION_JSON).content("{\"versione\": 1}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Il panel ha già scelto la versione 2"));
    }

    @Test
    void regolaDiDominioViolataRisponde422() throws Exception {
        when(servizio.crea(anyString(), anyString(), anyList()))
                .thenThrow(new FormulazioneNonValida("Ogni prova deve contenere la botanica di stagione (lavanda)"));

        mvc.perform(come("DISTILLATORE", post("/formulazioni").contentType(MediaType.APPLICATION_JSON).content(NUOVA_FORMULAZIONE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Formulazione non valida"));
    }

    // ---------------------------------------------------------------- supporto

    private static MockHttpServletRequestBuilder come(String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token(ruolo));
    }

    private static FormulazioneVista vista(StatoFormulazione stato, Integer versioneScelta) {
        List<Dose> dosi = List.of(new Dose("lavanda", new BigDecimal("20")));
        EsitoProva esito2 = versioneScelta == null ? EsitoProva.IN_ASSAGGIO : EsitoProva.SCELTA;
        EsitoProva esito1 = versioneScelta == null ? EsitoProva.IN_ASSAGGIO : EsitoProva.ARCHIVIATA;
        return new FormulazioneVista(ID, "Edizione d'estate", "lavanda", stato, versioneScelta, null, List.of(
                new FormulazioneVista.Prova(1, esito1, Instant.now(), dosi),
                new FormulazioneVista.Prova(2, esito2, Instant.now(), dosi)));
    }
}
