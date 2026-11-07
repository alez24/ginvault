package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una formulazione di Ricette vista da Edizioni: solo i dati che servono a
 * decidere se si può creare un'edizione e a calcolarne la tiratura.
 * È il "modello tradotto" dell'Anti-Corruption Layer (vedi CatalogoRicette):
 * la produce l'adapter, non viene mai salvata.
 *
 * @param grammiPerBottiglia grammi di botanica di stagione per bottiglia nella
 *                           versione scelta (null se non c'è ancora una scelta)
 * @param edizioneId         l'edizione che l'ha usata (null se non è USATA)
 */
public record FormulazioneInCatalogo(UUID id, Stato stato, Integer versioneScelta, String botanicaDiStagione,
                                     BigDecimal grammiPerBottiglia, UUID edizioneId) {

    public enum Stato {
        /** Il panel non ha ancora scelto (in Ricette: IN_PROVA). */
        DA_SCEGLIERE,
        /** Il panel ha scelto una versione: si può usare per un'edizione. */
        SCELTA,
        /** Già usata da un'edizione: non si riusa mai. */
        USATA
    }

    /**
     * Regola di Edizioni: un'edizione nasce solo da una formulazione SCELTA, e
     * proprio dalla versione che il panel ha scelto.
     *
     * @return il riferimento (id + versione) che l'Edizione conserverà
     * @throws FormulazioneNonUtilizzabile non ancora scelta, o versione diversa da quella scelta (-> 422)
     * @throws FormulazioneGiaUsata        già usata da un'altra edizione (-> 409)
     */
    public RiferimentoFormulazione riferimentoPer(int versioneRichiesta) {
        switch (stato) {
            case DA_SCEGLIERE -> throw new FormulazioneNonUtilizzabile(
                    "La formulazione " + id + " non è ancora stata scelta dal panel: non si può usare per un'edizione");
            case USATA -> throw new FormulazioneGiaUsata(
                    "La formulazione " + id + " è già stata usata per l'edizione " + edizioneId
                            + ": una formulazione usata non si riusa mai");
            case SCELTA -> {
                // si può usare: restano da controllare versione e dose
            }
        }
        if (versioneScelta == null || versioneScelta != versioneRichiesta) {
            throw new FormulazioneNonUtilizzabile("La versione " + versioneRichiesta
                    + " non è quella scelta dal panel (" + versioneScelta + ")");
        }
        if (grammiPerBottiglia == null || grammiPerBottiglia.signum() <= 0) {
            throw new FormulazioneNonUtilizzabile("La versione " + versioneScelta + " non indica i grammi di "
                    + botanicaDiStagione + " per bottiglia: non si può calcolare la tiratura");
        }
        return new RiferimentoFormulazione(id, versioneRichiesta);
    }
}
