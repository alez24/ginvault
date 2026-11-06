package it.ginvault.edizioni.dominio;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta del dominio verso Ricette: ANTI-CORRUPTION LAYER.
 *
 * Il dominio di Edizioni parla solo con questa interfaccia e solo nel proprio
 * linguaggio (FormulazioneInCatalogo, RiferimentoFormulazione). Come Ricette
 * espone i dati (URL, JSON, codici HTTP, nomi degli stati) lo sa solo
 * l'adapter REST (infrastruttura.ricette.CatalogoRicetteRest): se Ricette
 * cambia il suo modello, si cambia l'adapter, non il dominio.
 *
 * Errori, uguali per qualsiasi implementazione:
 *  - CatalogoRicetteNonDisponibile: Ricette non risponde, va in timeout o
 *    risponde in modo inatteso
 *  - FormulazioneGiaUsata: segnaUsata per un'edizione diversa da quella che
 *    l'ha già usata
 */
public interface CatalogoRicette {

    /** La formulazione, tradotta nel modello di Edizioni; vuoto se in Ricette non esiste. */
    Optional<FormulazioneInCatalogo> trova(UUID formulazioneId);

    /**
     * Segna in Ricette che la formulazione è usata da questa edizione: da quel
     * momento non si riusa più. Idempotente: ripeterla per la STESSA edizione
     * va bene (es. dopo un timeout), per un'edizione diversa è un conflitto.
     */
    void segnaUsata(RiferimentoFormulazione formulazione, UUID edizioneId);
}
