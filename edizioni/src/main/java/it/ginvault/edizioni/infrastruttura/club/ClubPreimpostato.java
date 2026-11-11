package it.ginvault.edizioni.infrastruttura.club;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import it.ginvault.edizioni.dominio.Club;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.Ritiro;

import org.springframework.stereotype.Service;

/**
 * Il Club come MOCK dentro Edizioni (docs/piano.md: "Esperienze & Club",
 * ammesso dalle linee guida come il MenuService degli esempi del corso).
 * Non ha un database né un servizio suo: legge dati preimpostati.
 *
 *  - prelazione dei soci: 7 giorni dall'annuncio;
 *  - al massimo 2 bottiglie per socio in prelazione;
 *  - il ritiro è una masterclass in distilleria, il primo sabato dopo almeno
 *    sei settimane dall'annuncio (il tempo di distillare, imbottigliare e
 *    controllare il lotto), dalle 18 alle 20. Per un'edizione annunciata
 *    l'8 luglio 2026: sabato 22 agosto.
 */
@Service
public class ClubPreimpostato implements Club {

    private static final ZoneId ROMA = ZoneId.of("Europe/Rome");
    private static final Duration PRELAZIONE = Duration.ofDays(7);
    private static final int BOTTIGLIE_PER_SOCIO = 2;
    private static final Period ATTESA_PER_IL_RITIRO = Period.ofWeeks(6);
    private static final int ORA_DI_INIZIO = 18;
    private static final Duration DURATA_DEL_RITIRO = Duration.ofHours(2);

    /** Il tema della masterclass per la botanica di stagione; per le altre, quello generico. */
    private static final Map<String, String> TEMI = Map.of(
            "lavanda", "La lavanda nel gin",
            "genziana", "La genziana nel gin",
            "pino mugo", "Il pino mugo nel gin",
            "ginepro", "Il ginepro di montagna");
    private static final String TEMA_GENERICO = "Le botaniche di stagione nel gin";

    /** I mesi che in italiano vogliono "d’": "masterclass d’agosto", ma "di settembre". */
    private static final Set<Month> MESI_CON_APOSTROFO = Set.of(Month.APRIL, Month.AUGUST, Month.OCTOBER);

    @Override
    public Duration durataPrelazione() {
        return PRELAZIONE;
    }

    @Override
    public int bottigliePerSocio() {
        return BOTTIGLIE_PER_SOCIO;
    }

    @Override
    public Ritiro ritiroPer(Edizione edizione) {
        LocalDate annuncio = edizione.creataIl().atZone(ROMA).toLocalDate();
        LocalDate giorno = annuncio.plus(ATTESA_PER_IL_RITIRO).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        OffsetDateTime inizio = giorno.atTime(ORA_DI_INIZIO, 0).atZone(ROMA).toOffsetDateTime();
        OffsetDateTime fine = inizio.plus(DURATA_DEL_RITIRO);
        return new Ritiro(
                "masterclass " + delMese(giorno.getMonth()),
                TEMI.getOrDefault(edizione.botanicaDiStagione(), TEMA_GENERICO),
                "Due ore in distilleria con il mastro distillatore: dalla botanica di stagione appena raccolta"
                        + " alla prova d’assaggio, fino all’edizione nel bicchiere. Alla fine ritiri le tue bottiglie.",
                inizio,
                fine,
                "In distilleria, nella sala degli alambicchi",
                "Un documento d’identità: le bottiglie sono intestate a te.");
    }

    private static String delMese(Month mese) {
        String nome = mese.getDisplayName(TextStyle.FULL, Locale.ITALIAN);
        return MESI_CON_APOSTROFO.contains(mese) ? "d’" + nome : "di " + nome;
    }
}
