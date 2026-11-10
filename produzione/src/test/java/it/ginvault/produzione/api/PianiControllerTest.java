package it.ginvault.produzione.api;

import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import java.util.UUID;

import io.grpc.Status;

import it.ginvault.produzione.applicazione.MagazzinoNonDisponibile;
import it.ginvault.produzione.applicazione.PianoNonTrovato;
import it.ginvault.produzione.applicazione.RiservaNonRiuscita;
import it.ginvault.produzione.applicazione.ServizioIntegrazioni;
import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.applicazione.Viste.ConsegnaVista;
import it.ginvault.produzione.applicazione.Viste.EsitoIntegrazioneVista;
import it.ginvault.produzione.applicazione.Viste.FormulazioneVista;
import it.ginvault.produzione.applicazione.Viste.LottiDelPianoVista;
import it.ginvault.produzione.applicazione.Viste.LottoDelPianoVista;
import it.ginvault.produzione.applicazione.Viste.PianoVista;
import it.ginvault.produzione.dominio.EsitoIntegrazione;
import it.ginvault.produzione.dominio.IntegrazioneRifiutata;
import it.ginvault.produzione.dominio.IntegrazioneRifiutata.Motivo;
import it.ginvault.produzione.sicurezza.GatewayDiProva;
import it.ginvault.produzione.sicurezza.SecurityConfig;

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

/**
 * L'API dei piani: JSON, ruoli, errori. I servizi sono finti (Mockito): il
 * gRPC vero è in IntegrazioneLottiTest.
 */
@WebMvcTest(PianiController.class)
@Import(SecurityConfig.class)
class PianiControllerTest {

    private static final UUID PIANO = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID EDIZIONE = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID LOTTO_DEI_CAMPI = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String ORIGINE = "Alta Val Varaita (Alpi Cozie)";
    private static final String CAMPI = "Campi GinVault (Saluzzo, 400 m)";
    private static final String INTEGRAZIONE = "{\"lottoId\": \"" + LOTTO_DEI_CAMPI + "\", \"kg\": 1.2}";

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

    @MockitoBean
    private ServizioIntegrazioni integrazioni;

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

    // ------------------------------------------------------------- integrazione di un lotto

    @Test
    void ilLottoDeiCampiVieneRifiutatoCon409ELaSpiegazione() throws Exception {
        when(integrazioni.integra(PIANO, LOTTO_DEI_CAMPI, new BigDecimal("1.2"))).thenThrow(
                new IntegrazioneRifiutata(Motivo.ORIGINE_DIVERSA,
                        "origine diversa: l'edizione deve restare fedele alla sua origine",
                        Map.of("origineRichiesta", ORIGINE, "origineLotto", CAMPI)));

        mvc.perform(integra("DISTILLATORE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Integrazione rifiutata"))
                .andExpect(jsonPath("$.motivo").value("ORIGINE_DIVERSA"))
                .andExpect(jsonPath("$.detail").value("origine diversa: l'edizione deve restare fedele alla sua origine"))
                .andExpect(jsonPath("$.origineRichiesta").value(ORIGINE))
                .andExpect(jsonPath("$.origineLotto").value(CAMPI));
    }

    @Test
    void unLottoIntegratoRisponde201ConIlPianoAggiornato() throws Exception {
        when(integrazioni.integra(PIANO, LOTTO_DEI_CAMPI, new BigDecimal("1.2")))
                .thenReturn(new EsitoIntegrazioneVista(EsitoIntegrazione.RESA_RICALCOLATA, piano()));

        mvc.perform(integra("DISTILLATORE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.esito").value("RESA_RICALCOLATA"))
                .andExpect(jsonPath("$.piano.id").value(PIANO.toString()));
    }

    @Test
    void loStessoLottoRimandatoRisponde200() throws Exception {
        when(integrazioni.integra(PIANO, LOTTO_DEI_CAMPI, new BigDecimal("1.2")))
                .thenReturn(new EsitoIntegrazioneVista(EsitoIntegrazione.GIA_INTEGRATO, piano()));

        mvc.perform(integra("ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.esito").value("GIA_INTEGRATO"));
    }

    @Test
    void soloIlDistillatoreIntegraUnLotto() throws Exception {
        mvc.perform(integra("RESP_ACQUISTI")).andExpect(status().isForbidden());
        verifyNoInteractions(integrazioni);
    }

    @Test
    void kgMancantiONegativiRispondono400SenzaChiamareIlMagazzino() throws Exception {
        for (String corpo : List.of("{\"lottoId\": \"" + LOTTO_DEI_CAMPI + "\"}",
                "{\"lottoId\": \"" + LOTTO_DEI_CAMPI + "\", \"kg\": -1}",
                "{\"kg\": 1.2}")) {
            mvc.perform(post("/piani/{id}/integrazioni", PIANO).contentType(MediaType.APPLICATION_JSON).content(corpo)
                            .header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(integrazioni);
    }

    @Test
    void ilMagazzinoCheNonRispondeDiventa503ConRetryAfter() throws Exception {
        when(integrazioni.integra(any(), any(), any())).thenThrow(new MagazzinoNonDisponibile(
                "Il magazzino non ha risposto entro 2 secondi: riprova fra poco",
                Status.DEADLINE_EXCEEDED.asRuntimeException()));

        mvc.perform(integra("DISTILLATORE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.title").value("Magazzino non disponibile"))
                .andExpect(jsonPath("$.detail").value("Il magazzino non ha risposto entro 2 secondi: riprova fra poco"));
    }

    @Test
    void unRifiutoDelMagazzinoDiventa409() throws Exception {
        when(integrazioni.integra(any(), any(), any())).thenThrow(new RiservaNonRiuscita("FAILED_PRECONDITION",
                "Il lotto ha 0,8 kg liberi: non se ne possono riservare 1,2"));

        mvc.perform(integra("DISTILLATORE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.motivo").value("RISERVA_RIFIUTATA"))
                .andExpect(jsonPath("$.statoGrpc").value("FAILED_PRECONDITION"));
    }

    @Test
    void ancheElenaVedeILottiDelPianoConLOrigine() throws Exception {
        when(integrazioni.lotti(PIANO)).thenReturn(new LottiDelPianoVista(PIANO, "lavanda", ORIGINE,
                new BigDecimal("1.200"), List.of(new LottoDelPianoVista(LOTTO_DEI_CAMPI, CAMPI,
                new BigDecimal("2.000"), new BigDecimal("2.000"), LocalDate.of(2027, 7, 31), "Campi GinVault",
                false))));

        mvc.perform(get("/piani/{id}/lotti", PIANO).header(HttpHeaders.AUTHORIZATION, bearer("RESP_ACQUISTI")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kgMancanti").value(1.2))
                .andExpect(jsonPath("$.lotti[0].origine").value(CAMPI))
                .andExpect(jsonPath("$.lotti[0].stessaOrigine").value(false));
    }

    private static org.springframework.test.web.servlet.RequestBuilder integra(String ruolo) {
        return post("/piani/{id}/integrazioni", PIANO).contentType(MediaType.APPLICATION_JSON).content(INTEGRAZIONE)
                .header(HttpHeaders.AUTHORIZATION, bearer(ruolo));
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
                new BigDecimal("10.800"), new BigDecimal("1.200"), 600, 540, List.of(consegna), List.of(), adesso,
                adesso);
    }
}
