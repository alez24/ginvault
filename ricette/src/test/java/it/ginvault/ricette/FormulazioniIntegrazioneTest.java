package it.ginvault.ricette;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import it.ginvault.ricette.sicurezza.GatewayDiProva;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test di integrazione: applicazione completa + PostgreSQL VERO (Testcontainers
 * avvia postgres:17-alpine in un container usa-e-getta; serve Docker).
 * Verifica che l'aggregato si salvi e si ricarichi correttamente (prove, dosi,
 * stato) e che le versioni vecchie restino intatte anche nelle tabelle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FormulazioniIntegrazioneTest {

    // @ServiceConnection: Spring Boot prende URL, utente e password dal container
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void laStoriaDiMarcoSulDatabaseVero() throws Exception {
        // Tre prove con la lavanda dell'anno prima
        String id = JsonPath.read(scrivi("/formulazioni", """
                {"nome": "Edizione d'estate", "botanicaDiStagione": "lavanda",
                 "dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 18},
                          {"botanica": "ginepro",  "grammiPerBottiglia": 12}]}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        scrivi("/formulazioni/" + id + "/versioni", """
                {"dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 20},
                          {"botanica": "ginepro",  "grammiPerBottiglia": 10}]}
                """).andExpect(status().isCreated());
        scrivi("/formulazioni/" + id + "/versioni", """
                {"dosi": [{"botanica": "lavanda", "grammiPerBottiglia": 24},
                          {"botanica": "ginepro",  "grammiPerBottiglia": 9}]}
                """).andExpect(status().isCreated());

        // Il panel sceglie la seconda
        scrivi("/formulazioni/" + id + "/scelta", "{\"versione\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("SCELTA"));

        // Riletta dal database (nuova transazione)
        mvc.perform(get("/formulazioni/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versioneScelta").value(2))
                .andExpect(jsonPath("$.prove.length()").value(3))
                .andExpect(jsonPath("$.prove[0].esito").value("ARCHIVIATA"))
                .andExpect(jsonPath("$.prove[1].esito").value("SCELTA"))
                .andExpect(jsonPath("$.prove[2].esito").value("ARCHIVIATA"))
                .andExpect(jsonPath("$.prove[1].dosi[0].botanica").value("lavanda"))
                .andExpect(jsonPath("$.prove[1].dosi[0].grammiPerBottiglia").value(20.0));

        // Nelle tabelle: tre prove, e la prima ha ancora i suoi 18 g di lavanda
        UUID uuid = UUID.fromString(id);
        assertThat(jdbc.queryForObject("select count(*) from prova_di_assaggio where formulazione_id = ?",
                Integer.class, uuid)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select d.grammi_per_bottiglia from dose d join prova_di_assaggio p on p.id = d.prova_id
                where p.formulazione_id = ? and p.versione = 1 and d.botanica = 'lavanda'
                """, BigDecimal.class, uuid)).isEqualByComparingTo("18");

        // Usata per un'edizione: non si riusa mai (ma ripetere per la stessa edizione va bene)
        UUID edizioneEstate = UUID.randomUUID();
        scrivi("/formulazioni/" + id + "/utilizzo", "{\"edizioneId\": \"" + edizioneEstate + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("USATA"));
        scrivi("/formulazioni/" + id + "/utilizzo", "{\"edizioneId\": \"" + edizioneEstate + "\"}")
                .andExpect(status().isOk());
        scrivi("/formulazioni/" + id + "/utilizzo", "{\"edizioneId\": \"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("non si riusa mai")));

        assertThat(jdbc.queryForObject("select stato from formulazione where id = ?", String.class, uuid))
                .isEqualTo("USATA");
        assertThat(jdbc.queryForObject("select edizione_id from formulazione where id = ?", UUID.class, uuid))
                .isEqualTo(edizioneEstate);
    }

    @Test
    void dopoLaSceltaUnaNuovaVersioneRisponde409ENonArrivaAlDatabase() throws Exception {
        String id = JsonPath.read(scrivi("/formulazioni", """
                {"nome": "Edizione d'autunno", "botanicaDiStagione": "rosmarino",
                 "dosi": [{"botanica": "rosmarino", "grammiPerBottiglia": 6}]}
                """).andReturn().getResponse().getContentAsString(), "$.id");
        scrivi("/formulazioni/" + id + "/scelta", "{\"versione\": 1}").andExpect(status().isOk());

        scrivi("/formulazioni/" + id + "/versioni", """
                {"dosi": [{"botanica": "rosmarino", "grammiPerBottiglia": 7}]}
                """).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from prova_di_assaggio where formulazione_id = ?",
                Integer.class, UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void dietroIlGatewayLaLocationPuntaAllIndirizzoPubblico() throws Exception {
        mvc.perform(post("/formulazioni")
                        .header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE"))
                        // gli header che aggiunge il gateway dopo aver tolto /api/ricette (Compose)
                        .header("X-Forwarded-Host", "localhost:8080")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Port", "8080")
                        .header("X-Forwarded-Prefix", "/api/ricette")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nome": "Edizione d'inverno", "botanicaDiStagione": "pino",
                                 "dosi": [{"botanica": "pino", "grammiPerBottiglia": 5}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        matchesPattern("http://localhost:8080/api/ricette/formulazioni/[0-9a-f-]{36}")));
    }

    @Test
    void lElencoFiltraPerStatoSulDatabaseVero() throws Exception {
        String inProva = crea("Prova d'elenco in corso", "salvia");
        String scelta = crea("Prova d'elenco scelta", "timo");
        scrivi("/formulazioni/" + scelta + "/scelta", "{\"versione\": 1}").andExpect(status().isOk());

        // Tutte: ci sono entrambe (più quelle degli altri test, stesso database)
        String tutte = mvc.perform(get("/formulazioni").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(tutte, "$[*].id")).contains(inProva, scelta);

        // Solo le SCELTA: c'è quella scelta e non quella in prova
        String soloScelte = mvc.perform(get("/formulazioni").param("stato", "SCELTA")
                        .header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].stato", everyItem(is("SCELTA"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(soloScelte, "$[*].id")).contains(scelta).doesNotContain(inProva);
    }

    @Test
    void formulazioneInesistenteRisponde404() throws Exception {
        mvc.perform(get("/formulazioni/{id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /** Una formulazione IN_PROVA con una sola prova; restituisce l'id. */
    private String crea(String nome, String botanica) throws Exception {
        return JsonPath.read(scrivi("/formulazioni", """
                {"nome": "%s", "botanicaDiStagione": "%s",
                 "dosi": [{"botanica": "%s", "grammiPerBottiglia": 8}]}
                """.formatted(nome, botanica, botanica))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions scrivi(String percorso, String json) throws Exception {
        return mvc.perform(post(percorso)
                .header(HttpHeaders.AUTHORIZATION, bearer("DISTILLATORE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String bearer(String ruolo) {
        return "Bearer " + GATEWAY.token(ruolo);
    }
}
