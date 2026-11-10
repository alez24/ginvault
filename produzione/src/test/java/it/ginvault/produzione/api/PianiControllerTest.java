package it.ginvault.produzione.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.applicazione.PianoNonTrovato;
import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.applicazione.Viste.ConsegnaVista;
import it.ginvault.produzione.applicazione.Viste.FormulazioneVista;
import it.ginvault.produzione.applicazione.Viste.PianoVista;
import it.ginvault.produzione.sicurezza.GatewayDiProva;
import it.ginvault.produzione.sicurezza.SecurityConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * L'API dei piani: JSON, ruoli, errori. Il servizio è finto (Mockito).
 */
@WebMvcTest(PianiController.class)
@Import(SecurityConfig.class)
class PianiControllerTest {

    private static final UUID PIANO = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();

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
    private ServizioProduzione servizio;

    @Test
    void ilDistillatoreVedeIlPianoConLaConsegnaRidotta() throws Exception {
        when(servizio.piani(EDIZIONE)).thenReturn(List.of(piano()));

        mvc.perform(get("/piani").param("edizione", EDIZIONE.toString()).header(HttpHeaders.AUTHORIZATION,
                        bearer("DISTILLATORE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PIANO.toString()))
                .andExpect(jsonPath("$[0].edizioneId").value(EDIZIONE.toString()))
                .andExpect(jsonPath("$[0].kgPrevisti").value(12.0))
                .andExpect(jsonPath("$[0].kgDisponibili").value(10.8))
                .andExpect(jsonPath("$[0].bottiglieProducibili").value(540))
                .andExpect(jsonPath("$[0].origineRichiesta").value("Alta Val Varaita (Alpi Cozie)"))
                .andExpect(jsonPath("$[0].consegne[0].motivo").value("consegna ridotta: 10,8 su 12 kg"));
    }

    @Test
    void ancheResponsabileAcquistiEAdminLeggonoIPiani() throws Exception {
        when(servizio.piano(PIANO)).thenReturn(piano());

        for (String ruolo : List.of("RESP_ACQUISTI", "ADMIN")) {
            mvc.perform(get("/piani/{id}", PIANO).header(HttpHeaders.AUTHORIZATION, bearer(ruolo)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.formulazione.versione").value(2));
        }
    }

    @Test
    void unSocioNonVedeIPiani() throws Exception {
        mvc.perform(get("/piani").header(HttpHeaders.AUTHORIZATION, bearer("SOCIO")))
                .andExpect(status().isForbidden());
    }

    @Test
    void senzaTokenRisponde401() throws Exception {
        mvc.perform(get("/piani")).andExpect(status().isUnauthorized());
    }

    @Test
    void unPianoCheNonCeRisponde404() throws Exception {
        when(servizio.piano(any())).thenThrow(new PianoNonTrovato("Nessun piano di produzione con id " + PIANO));

        mvc.perform(get("/piani/{id}", PIANO).header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Piano non trovato"));
    }

    @Test
    void unEdizioneNonUuidRisponde400() throws Exception {
        mvc.perform(get("/piani").param("edizione", "estate").header(HttpHeaders.AUTHORIZATION,
                        bearer("DISTILLATORE")))
                .andExpect(status().isBadRequest());
    }

    private static String bearer(String ruolo) {
        return "Bearer " + GATEWAY.token(ruolo);
    }

    private static PianoVista piano() {
        ConsegnaVista consegna = new ConsegnaVista(UUID.randomUUID(), UUID.randomUUID(),
                "Alta Val Varaita (Alpi Cozie)", new BigDecimal("12.000"), new BigDecimal("10.800"),
                new BigDecimal("10.800"), "consegna ridotta: 10,8 su 12 kg", LocalDate.of(2026, 8, 6));
        Instant adesso = Instant.parse("2026-08-06T08:30:00Z");
        return new PianoVista(PIANO, EDIZIONE, new FormulazioneVista(UUID.randomUUID(), 2), "lavanda",
                new BigDecimal("20.00"), "Alta Val Varaita (Alpi Cozie)", new BigDecimal("12.000"),
                new BigDecimal("10.800"), 600, 540, List.of(consegna), adesso, adesso);
    }
}
