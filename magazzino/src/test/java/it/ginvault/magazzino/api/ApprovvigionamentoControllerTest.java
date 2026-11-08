package it.ginvault.magazzino.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import it.ginvault.magazzino.applicazione.FornitoreNonTrovato;
import it.ginvault.magazzino.applicazione.OrdineNonTrovato;
import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.applicazione.Viste.ConsegnaVista;
import it.ginvault.magazzino.applicazione.Viste.EsitoConsegna;
import it.ginvault.magazzino.applicazione.Viste.LottoVista;
import it.ginvault.magazzino.applicazione.Viste.OrdineVista;
import it.ginvault.magazzino.applicazione.Viste.RiferimentoFornitore;
import it.ginvault.magazzino.dominio.ConsegnaNonValida;
import it.ginvault.magazzino.dominio.DatiConsegna;
import it.ginvault.magazzino.dominio.EsitoQualita;
import it.ginvault.magazzino.dominio.OrdineGiaConsegnato;
import it.ginvault.magazzino.dominio.OrdineNonValido;
import it.ginvault.magazzino.dominio.StatoOrdine;
import it.ginvault.magazzino.sicurezza.GatewayDiProva;
import it.ginvault.magazzino.sicurezza.SecurityConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Strato web di Magazzino: ruoli (401/403/2xx) su ordini, consegne, lotti e
 * fornitori, validazione dei corpi (400) e traduzione degli errori di dominio
 * in 404/409/422. Il servizio applicativo è un mock: database e Kafka sono nel
 * test di integrazione.
 */
@WebMvcTest({OrdiniController.class, LottiController.class, FornitoriController.class})
@Import(SecurityConfig.class)
class ApprovvigionamentoControllerTest {

    private static final GatewayDiProva GATEWAY = new GatewayDiProva();
    private static final UUID ORDINE = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID LOTTO = UUID.fromString("a0000000-0000-4000-8000-000000000099");
    private static final UUID FORNITORE = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");

    private static final String NUOVO_ORDINE = """
            {"fornitoreId": "f0000000-0000-4000-8000-000000000001", "botanica": "lavanda",
             "kgOrdinati": 12, "consegnaAttesa": "2026-07-31",
             "edizioneId": "e0000000-0000-4000-8000-000000000001"}
            """;

    private static final String NUOVA_CONSEGNA = """
            {"kgArrivati": 10.8, "consegnataIl": "2026-08-06", "scadenza": "2027-08-31",
             "esitoQualita": "CONFORME", "nota": "pioggia durante la fioritura"}
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
    private ServizioApprovvigionamento servizio;

    // ---------------------------------------------------------------- sicurezza

    @Test
    void senzaTokenRisponde401() throws Exception {
        mvc.perform(json(post("/ordini"), NUOVO_ORDINE)).andExpect(status().isUnauthorized());
        mvc.perform(get("/lotti")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fornitori/{id}", FORNITORE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servizio);
    }

    @Test
    void ordinareERegistrareConsegneSpettaSoloAllaResponsabileAcquisti() throws Exception {
        for (String ruolo : List.of("DISTILLATORE", "ADMIN", "SOCIO", "CLIENTE")) {
            mvc.perform(come(ruolo, json(post("/ordini"), NUOVO_ORDINE))).andExpect(status().isForbidden());
            mvc.perform(come(ruolo, json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(servizio);
    }

    @Test
    void leggonoAncheDistillatoreEAdminMaNonSocioECliente() throws Exception {
        when(servizio.lotti("lavanda")).thenReturn(List.of(lotto()));

        for (String ruolo : List.of("RESP_ACQUISTI", "DISTILLATORE", "ADMIN")) {
            mvc.perform(come(ruolo, get("/lotti").param("botanica", "lavanda"))).andExpect(status().isOk());
            mvc.perform(come(ruolo, get("/ordini"))).andExpect(status().isOk());
            mvc.perform(come(ruolo, get("/fornitori"))).andExpect(status().isOk());
        }
        for (String ruolo : List.of("SOCIO", "CLIENTE")) {
            mvc.perform(come(ruolo, get("/lotti"))).andExpect(status().isForbidden());
            mvc.perform(come(ruolo, get("/ordini/{id}", ORDINE))).andExpect(status().isForbidden());
            mvc.perform(come(ruolo, get("/fornitori/{id}", FORNITORE))).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- casi ok

    @Test
    void nuovoOrdineRisponde201ConLocation() throws Exception {
        when(servizio.creaOrdine(eq(FORNITORE), eq("lavanda"), any(), eq(LocalDate.of(2026, 7, 31)), eq(EDIZIONE)))
                .thenReturn(ordine(null));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini"), NUOVO_ORDINE)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/ordini/" + ORDINE))
                .andExpect(jsonPath("$.stato").value("APERTO"))
                .andExpect(jsonPath("$.fornitore.nome").value("Raccoglitori dell'Alta Val Varaita"))
                .andExpect(jsonPath("$.consegna").doesNotExist());
    }

    @Test
    void laConsegnaRidottaRisponde201ConIlLottoNuovo() throws Exception {
        when(servizio.registraConsegna(eq(ORDINE), any())).thenReturn(new EsitoConsegna(ordine(consegna()), lotto()));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost/lotti/" + LOTTO))
                .andExpect(jsonPath("$.ordine.stato").value("CONSEGNATO"))
                .andExpect(jsonPath("$.ordine.consegna.ridotta").value(true))
                .andExpect(jsonPath("$.ordine.consegna.kgMancanti").value(1.2))
                .andExpect(jsonPath("$.lotto.kg").value(10.8));

        verify(servizio).registraConsegna(ORDINE, new DatiConsegna(new BigDecimal("10.8"), LocalDate.of(2026, 8, 6),
                LocalDate.of(2027, 8, 31), EsitoQualita.CONFORME, "pioggia durante la fioritura"));
    }

    // ---------------------------------------------------------------- 400: forma della richiesta

    @Test
    void campiMancantiONegativiRispondono400ConLElenco() throws Exception {
        String sbagliato = """
                {"botanica": "", "kgOrdinati": -1, "consegnaAttesa": "2026-07-31"}
                """;

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini"), sbagliato)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Richiesta non valida"))
                .andExpect(jsonPath("$.campi[*].campo").value(hasItem("fornitoreId")))
                .andExpect(jsonPath("$.campi[*].campo").value(hasItem("botanica")))
                .andExpect(jsonPath("$.campi[*].campo").value(hasItem("kgOrdinati")))
                .andExpect(jsonPath("$.campi[*].campo").value(hasItem("edizioneId")));
        verifyNoInteractions(servizio);
    }

    @Test
    void unEsitoQualitaSconosciutoRisponde400() throws Exception {
        String sbagliato = NUOVA_CONSEGNA.replace("CONFORME", "BUONO");

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), sbagliato)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servizio);
    }

    // ---------------------------------------------------------------- errori di dominio

    @Test
    void ordineOFornitoreInesistentiRispondono404() throws Exception {
        when(servizio.registraConsegna(eq(ORDINE), any())).thenThrow(new OrdineNonTrovato(ORDINE));
        when(servizio.fornitore(FORNITORE)).thenThrow(new FornitoreNonTrovato(FORNITORE));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ordine non trovato"));
        mvc.perform(come("RESP_ACQUISTI", get("/fornitori/{id}", FORNITORE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Fornitore non trovato"));
    }

    @Test
    void unaSecondaConsegnaRisponde409ConLaSpiegazione() throws Exception {
        when(servizio.registraConsegna(eq(ORDINE), any()))
                .thenThrow(new OrdineGiaConsegnato("L'ordine è già stato consegnato: ogni ordine ha una sola consegna"));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Ordine già consegnato"))
                .andExpect(jsonPath("$.detail").value(containsString("una sola consegna")));
    }

    @Test
    void dueConsegneNelloStessoIstanteRispondono409() throws Exception {
        when(servizio.registraConsegna(eq(ORDINE), any()))
                .thenThrow(new OptimisticLockingFailureException("revisione cambiata"));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Modifica concorrente"));
    }

    @Test
    void leRegoleDiDominioViolateRispondono422() throws Exception {
        when(servizio.registraConsegna(eq(ORDINE), any()))
                .thenThrow(new ConsegnaNonValida("Sono arrivati 12,5 kg ma ne erano stati ordinati 12"));
        when(servizio.creaOrdine(any(), any(), any(), any(), any()))
                .thenThrow(new OrdineNonValido("Il fornitore non esiste"));

        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini/{id}/consegne", ORDINE), NUOVA_CONSEGNA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Consegna non valida"))
                .andExpect(jsonPath("$.detail").value(containsString("12,5 kg")));
        mvc.perform(come("RESP_ACQUISTI", json(post("/ordini"), NUOVO_ORDINE)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Ordine non valido"));
    }

    // ---------------------------------------------------------------- supporto

    private static MockHttpServletRequestBuilder come(String ruolo, MockHttpServletRequestBuilder richiesta) {
        return richiesta.header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY.token(ruolo));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder richiesta, String corpo) {
        return richiesta.contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    private static RiferimentoFornitore fornitore() {
        return new RiferimentoFornitore(FORNITORE, "Raccoglitori dell'Alta Val Varaita");
    }

    private static OrdineVista ordine(ConsegnaVista consegna) {
        return new OrdineVista(ORDINE, fornitore(), "lavanda", new BigDecimal("12.000"), LocalDate.of(2026, 7, 31),
                EDIZIONE, consegna == null ? StatoOrdine.APERTO : StatoOrdine.CONSEGNATO, consegna,
                Instant.parse("2026-07-01T09:00:00Z"));
    }

    private static ConsegnaVista consegna() {
        return new ConsegnaVista(LocalDate.of(2026, 8, 6), new BigDecimal("10.800"), new BigDecimal("1.200"), true,
                true, 6, "Alta Val Varaita (Alpi Cozie)", LOTTO, "pioggia durante la fioritura");
    }

    private static LottoVista lotto() {
        return new LottoVista(LOTTO, "lavanda", "Alta Val Varaita (Alpi Cozie)", new BigDecimal("10.800"),
                LocalDate.of(2027, 8, 31), EsitoQualita.CONFORME, true, fornitore(), ORDINE, LocalDate.of(2026, 8, 6));
    }
}
