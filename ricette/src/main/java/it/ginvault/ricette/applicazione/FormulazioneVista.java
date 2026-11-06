package it.ginvault.ricette.applicazione;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import it.ginvault.ricette.dominio.Dose;
import it.ginvault.ricette.dominio.EsitoProva;
import it.ginvault.ricette.dominio.Formulazione;
import it.ginvault.ricette.dominio.ProvaDiAssaggio;
import it.ginvault.ricette.dominio.StatoFormulazione;

/**
 * Fotografia in sola lettura di una formulazione, restituita dalle API.
 *
 * Viene costruita DENTRO la transazione (le prove si caricano dal DB solo quando
 * servono, e con open-in-view disattivato fuori dalla transazione non si
 * potrebbe più): così il controller non tocca mai le entità JPA.
 *
 * È anche il "contratto" che legge Edizioni: stato, versione scelta e dosi.
 */
public record FormulazioneVista(
        UUID id,
        String nome,
        String botanicaDiStagione,
        StatoFormulazione stato,
        Integer versioneScelta,
        UUID edizioneId,
        List<Prova> prove) {

    public record Prova(int versione, EsitoProva esito, Instant registrataIl, List<Dose> dosi) {
    }

    static FormulazioneVista di(Formulazione formulazione) {
        List<Prova> prove = formulazione.prove().stream()
                .map(prova -> new Prova(prova.versione(), formulazione.esitoDi(prova), prova.registrataIl(),
                        List.copyOf(prova.dosi())))
                .toList();
        return new FormulazioneVista(
                formulazione.id(),
                formulazione.nome(),
                formulazione.botanicaDiStagione(),
                formulazione.stato(),
                formulazione.provaScelta().map(ProvaDiAssaggio::versione).orElse(null),
                formulazione.edizione().orElse(null),
                prove);
    }
}
