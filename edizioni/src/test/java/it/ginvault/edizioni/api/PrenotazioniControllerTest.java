package it.ginvault.edizioni.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import it.ginvault.edizioni.applicazione.EdizioneNonTrovata;
import it.ginvault.edizioni.applicazione.PrenotazioneNonTrovata;
import it.ginvault.edizioni.applicazione.PrenotazioneVista;
import it.ginvault.edizioni.applicazione.RegoleDiVenditaVista;
import it.ginvault.edizioni.applicazione.ServizioPrenotazioni;
import it.ginvault.edizioni.dominio.Acquirente;
import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.PrenotazioneNonPossibile;
import it.ginvault.edizioni.dominio.QuotaEsaurita;
import it.ginvault.edizioni.dominio.Ritiro;
import it.ginvault.edizioni.dominio.StatoPrenotazione;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Strato web delle prenotazioni: chi può prenotare (401/403), da dove viene
 * l'acquirente (il "sub" e il ruolo SOCIO del JWT), validazione del corpo
 * (400) e traduzione dei rifiuti in 409 con il motivo, 404.
 * Il servizio applicativo è un mock: database e regole sono negli altri test.
 */
@WebMvcTest(PrenotazioniController.class)
@Import(SecurityConfig.class)
class PrenotazioniControllerTest {

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID PRENOTAZIONE = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final String DUE_BOTTIGLIE = "{\"bottiglie\": 2}";

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
    private ServizioPrenotazioni servizio;

    // ---------------------------------------------------------------- chi può prenotare

    @Test
    void senzaTokenRisponde401() throws Exception {
        mvc.perform(post("/edizioni/{id}/prenotazioni", EDIZIONE).contentType(MediaType.APPLICATION_JSON)
                .content(DUE_BOTTIGLIE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/prenotazioni")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void distillatoreAdminEAcquistiNonPrenotano() throws Exception {
        for (String ruolo : List.of("DISTILLATORE", "ADMIN", "RESP_ACQUISTI")) {
            mvc.perform(come("github:42", ruolo, post("/edizioni/{id}/prenotazioni", EDIZIONE)
                    .contentType(MediaType.APPLICATION_JSON).content(DUE_BOTTIGLIE))).andExpect(status().isForbidden());
            mvc.perform(come("github:42", ruolo, get("/prenotazioni"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(servizio);
    }

    @Test
    void ilSocioPrenotaComeSeStessoERiceve201ConLocation() throws Exception {
        when(servizio.prenota(eq(EDIZIONE), any(), eq(2))).thenReturn(vista());

        mvc.perform(come("github:1001", "SOCIO", post("/edizioni/{id}/prenotazioni", EDIZIONE)
                        .contentType(MediaType.APPLICATION_JSON).content(DUE_BOTTIGLIE)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/prenotazioni/" + PRENOTAZIONE))
                .andExpect(jsonPath("$.stato").value("IN_ATTESA_PAGAMENTO"))
                .andExpect(jsonPath("$.canale").value("SOCI"))
                .andExpect(jsonPath("$.totale").value(90.00))
                .andExpect(jsonPath("$.edizione.id").value(EDIZIONE.toString()))
                .andExpect(jsonPath("$.ritiro.inizio").value("2026-08-22T18:00:00+02:00"));

        verify(servizio).prenota(EDIZIONE, new Acquirente("github:1001", true), 2);
    }

    @Test
    void ilClienteNonESocio() throws Exception {
        when(servizio.prenota(eq(EDIZIONE), any(), eq(2))).thenReturn(vista());

        mvc.perform(come("google:2002", "CLIENTE", post("/edizioni/{id}/prenotazioni", EDIZIONE)
                .contentType(MediaType.APPLICATION_JSON).content(DUE_BOTTIGLIE))).andExpect(status().isCreated());

        verify(servizio).prenota(EDIZIONE, new Acquirente("google:2002", false), 2);
    }

    @Test
    void ogniPersonaLeggeLeSuePrenotazioni() throws Exception {
        when(servizio.sue("github:1001")).thenReturn(List.of(vista()));
        when(servizio.trova(PRENOTAZIONE, "github:1001")).thenReturn(vista());
        when(servizio.trova(PRENOTAZIONE, "google:2002")).thenThrow(new PrenotazioneNonTrovata(PRENOTAZIONE));

        mvc.perform(come("github:1001", "SOCIO", get("/prenotazioni")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(PRENOTAZIONE.toString()));
        mvc.perform(come("github:1001", "SOCIO", get("/prenotazioni/{id}", PRENOTAZIONE)))
                .andExpect(status().isOk());
        mvc.perform(come("google:2002", "CLIENTE", get("/prenotazioni/{id}", PRENOTAZIONE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Prenotazione non trovata"));
    }

    // ---------------------------------------------------------------- forma del corpo

    @Test
    void bottiglieMancantiOZeroRisponde400() throws Exception {
        for (String corpo : List.of("{}", "{\"bottiglie\": 0}", "{\"bottiglie\": -1}")) {
            mvc.perform(come("github:1001", "SOCIO", post("/edizioni/{id}/prenotazioni", EDIZIONE)
                            .contentType(MediaType.APPLICATION_JSON).content(corpo)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campi[0].campo").value("bottiglie"));
        }
        verifyNoInteractions(servizio);
    }

    // ---------------------------------------------------------------- rifiuti

    @Test
    void iRifiutiDelleRegoleDiventano409ConIlMotivo() throws Exception {
        when(servizio.prenota(eq(EDIZIONE), any(), anyInt()))
                .thenThrow(new PrenotazioneNonPossibile(PrenotazioneNonPossibile.Motivo.PRELAZIONE_DEI_SOCI,
                        "Fino al 15 luglio 2026 alle 10:24 l'edizione è in prelazione"))
                .thenThrow(new PrenotazioneNonPossibile(PrenotazioneNonPossibile.Motivo.LIMITE_PER_PERSONA,
                        "Troppe bottiglie"))
                .thenThrow(new QuotaEsaurita("Nel canale ONLINE restano 0 bottiglie libere"))
                .thenThrow(new EdizioneNonTrovata(EDIZIONE));

        prenotaComeCliente().andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Prenotazione non possibile"))
                .andExpect(jsonPath("$.motivo").value("PRELAZIONE_DEI_SOCI"))
                .andExpect(jsonPath("$.detail").value("Fino al 15 luglio 2026 alle 10:24 l'edizione è in prelazione"));
        prenotaComeCliente().andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("LIMITE_PER_PERSONA"));
        prenotaComeCliente().andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Quota esaurita"))
                .andExpect(jsonPath("$.motivo").value("QUOTA_ESAURITA"));
        prenotaComeCliente().andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- regole di vendita

    @Test
    void leRegoleDiVenditaLeLeggonoTuttiIRuoli() throws Exception {
        when(servizio.regole()).thenReturn(new RegoleDiVenditaVista(7, 2, 6, new BigDecimal("45.00"),
                new BigDecimal("52.00"), 15));

        for (String ruolo : List.of("SOCIO", "CLIENTE", "DISTILLATORE", "RESP_ACQUISTI", "ADMIN")) {
            mvc.perform(come("github:42", ruolo, get("/regole-di-vendita")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.giorniDiPrelazione").value(7))
                    .andExpect(jsonPath("$.bottigliePerSocio").value(2))
                    .andExpect(jsonPath("$.prezzoSoci").value(45.00))
                    .andExpect(jsonPath("$.minutiPerPagare").value(15));
        }
        mvc.perform(get("/regole-di-vendita")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- supporto

    private ResultActions prenotaComeCliente() throws Exception {
        return mvc.perform(come("google:2002", "CLIENTE", post("/edizioni/{id}/prenotazioni", EDIZIONE)
                .contentType(MediaType.APPLICATION_JSON).content(DUE_BOTTIGLIE)));
    }

    private static MockHttpServletRequestBuilder come(String sub, String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.tokenPer(sub, ruolo));
    }

    private static PrenotazioneVista vista() {
        Instant prenotata = Instant.parse("2026-07-09T08:24:00Z");
        Ritiro ritiro = new Ritiro("masterclass d’agosto", "La lavanda nel gin", "Due ore in distilleria",
                OffsetDateTime.parse("2026-08-22T18:00:00+02:00"), OffsetDateTime.parse("2026-08-22T20:00:00+02:00"),
                "In distilleria", "Un documento d’identità");
        return new PrenotazioneVista(PRENOTAZIONE,
                new PrenotazioneVista.EdizioneInBreve(EDIZIONE, "lavanda", "Alta Val Varaita (Alpi Cozie)", 600,
                        Instant.parse("2026-07-08T08:00:00Z")),
                Canale.SOCI, 2, new BigDecimal("45.00"), new BigDecimal("90.00"), StatoPrenotazione.IN_ATTESA_PAGAMENTO,
                prenotata, prenotata.plusSeconds(900), ritiro);
    }
}
