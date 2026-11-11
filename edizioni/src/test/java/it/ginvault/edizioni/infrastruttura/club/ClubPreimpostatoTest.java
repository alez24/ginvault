package it.ginvault.edizioni.infrastruttura.club;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo.Stato;
import it.ginvault.edizioni.dominio.Ritiro;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Il Club preimpostato: le sue regole e la data del ritiro, il primo sabato
 * dopo almeno sei settimane dall'annuncio, alle 18 di Roma.
 */
class ClubPreimpostatoTest {

    private final ClubPreimpostato club = new ClubPreimpostato();

    @Test
    void prelazioneDi7GiorniE2BottigliePerSocio() {
        assertThat(club.durataPrelazione()).isEqualTo(Duration.ofDays(7));
        assertThat(club.bottigliePerSocio()).isEqualTo(2);
    }

    @Test
    void lEdizioneDEstateAnnunciataL8LuglioSiRitiraAllaMasterclassDel22Agosto() {
        Edizione estate = annunciata("lavanda", ZonedDateTime.of(2026, 7, 8, 10, 0, 0, 0, ZoneId.of("Europe/Rome")));

        Ritiro ritiro = club.ritiroPer(estate);

        assertThat(ritiro.occasione()).isEqualTo("masterclass d’agosto");
        assertThat(ritiro.tema()).isEqualTo("La lavanda nel gin");
        assertThat(ritiro.inizio()).isEqualTo(OffsetDateTime.parse("2026-08-22T18:00:00+02:00"));
        assertThat(ritiro.fine()).isEqualTo(OffsetDateTime.parse("2026-08-22T20:00:00+02:00"));
        assertThat(ritiro.luogo()).isEqualTo("In distilleria, nella sala degli alambicchi");
        assertThat(ritiro.daPortare()).contains("documento d’identità");
    }

    @Test
    void dInvernoIlFusoEQuelloSolareEIlMeseVuoleIlDi() {
        Edizione inverno = annunciata("pino mugo", ZonedDateTime.of(2026, 11, 11, 9, 0, 0, 0, ZoneId.of("Europe/Rome")));

        Ritiro ritiro = club.ritiroPer(inverno);

        assertThat(ritiro.occasione()).isEqualTo("masterclass di dicembre");
        assertThat(ritiro.tema()).isEqualTo("Il pino mugo nel gin");
        assertThat(ritiro.inizio()).isEqualTo(OffsetDateTime.parse("2026-12-26T18:00:00+01:00"));
    }

    @Test
    void unaBotanicaSenzaTemaHaQuelloGenerico() {
        Edizione edizione = annunciata("rabarbaro", ZonedDateTime.of(2026, 3, 2, 9, 0, 0, 0, ZoneId.of("Europe/Rome")));

        assertThat(club.ritiroPer(edizione).tema()).isEqualTo("Le botaniche di stagione nel gin");
    }

    /** Un'edizione con la data di annuncio voluta (creataIl la fissa il costruttore con l'ora attuale). */
    private static Edizione annunciata(String botanica, ZonedDateTime quando) {
        Edizione edizione = Edizione.nuova(new FormulazioneInCatalogo(UUID.randomUUID(), Stato.SCELTA, 1, botanica,
                new BigDecimal("20"), null), 1, new BigDecimal("12"), "Alta Val Varaita (Alpi Cozie)",
                Map.of(Canale.SOCI, 300, Canale.BAR, 150, Canale.ONLINE, 150), Duration.ofDays(7));
        ReflectionTestUtils.setField(edizione, "creataIl", quando.toInstant());
        return edizione;
    }
}
