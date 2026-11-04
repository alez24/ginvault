package it.ginvault.pagamenti.sicurezza;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sicurezza del servizio: 401 senza JWT valido, 403 senza il ruolo giusto, 200 con.
 * @WebMvcTest carica solo lo strato web (niente database).
 */
@WebMvcTest(ProvaController.class)
@Import(SecurityConfig.class)
class SicurezzaTest {

    private static final String SERVIZIO = "pagamenti";
    private static final String RUOLO_RICHIESTO = "CLIENTE";
    private static final String ALTRO_RUOLO = "DISTILLATORE";

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

    @Test
    void senzaTokenRisponde401() throws Exception {
        mvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void conIlRuoloRichiestoRisponde200() throws Exception {
        mvc.perform(get("/prova").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.token(RUOLO_RICHIESTO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servizio").value(SERVIZIO));
    }

    @Test
    void conUnAltroRuoloRisponde403() throws Exception {
        mvc.perform(get("/prova").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.token(ALTRO_RUOLO))))
                .andExpect(status().isForbidden());
    }

    @Test
    void meMostraIdentitaERuoliLettiDalJwt() throws Exception {
        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.token(RUOLO_RICHIESTO, "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servizio").value(SERVIZIO))
                .andExpect(jsonPath("$.sub").value("github:42"))
                .andExpect(jsonPath("$.nome").value("Mario Rossi"))
                .andExpect(jsonPath("$.ruoli").value(org.hamcrest.Matchers.containsInAnyOrder(RUOLO_RICHIESTO, "ADMIN")));
    }

    @Test
    void tokenScadutoRisponde401() throws Exception {
        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.tokenScaduto(RUOLO_RICHIESTO))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDiUnAltroEmittenteRisponde401() throws Exception {
        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.tokenDiUnAltroEmittente(RUOLO_RICHIESTO))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenConFirmaFalsaRisponde401() throws Exception {
        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(GATEWAY.tokenConFirmaFalsa(RUOLO_RICHIESTO))))
                .andExpect(status().isUnauthorized());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
