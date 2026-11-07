package it.ginvault.ricette.dominio;

/**
 * Ciclo di vita di una formulazione. Si va solo avanti, mai indietro:
 *
 *   IN_PROVA ──scegli(versione)──▶ SCELTA ──usaPerEdizione(id)──▶ USATA
 *
 *  - IN_PROVA: il distillatore prepara le prove (versioni) per il panel.
 *  - SCELTA:   il panel ha scelto una versione; le altre restano archiviate.
 *  - USATA:    la formulazione è legata a un'edizione e non si riusa mai più.
 */
public enum StatoFormulazione {
    IN_PROVA,
    SCELTA,
    USATA
}
