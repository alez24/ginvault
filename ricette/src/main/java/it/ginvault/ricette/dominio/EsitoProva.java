package it.ginvault.ricette.dominio;

/**
 * Esito di una singola prova di assaggio, ricavato dallo stato della
 * formulazione (non è salvato: non può andare fuori sincrono).
 */
public enum EsitoProva {
    /** La formulazione è ancora IN_PROVA: il panel non ha deciso. */
    IN_ASSAGGIO,
    /** È la versione scelta dal panel. */
    SCELTA,
    /** Il panel ha scelto un'altra versione: questa resta in archivio, intatta. */
    ARCHIVIATA
}
