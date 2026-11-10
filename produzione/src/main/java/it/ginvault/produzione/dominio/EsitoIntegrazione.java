package it.ginvault.produzione.dominio;

/** Cosa ha fatto PianoDiProduzione.integraLotto. */
public enum EsitoIntegrazione {
    /** I kg disponibili sono cambiati: registrato ResaRicalcolata */
    RESA_RICALCOLATA,
    /** Integrato, ma i kg disponibili non cambiano (erano già al massimo) */
    RESA_INVARIATA,
    /** Il lotto era già integrato (richiesta ripetuta): niente di nuovo */
    GIA_INTEGRATO
}
