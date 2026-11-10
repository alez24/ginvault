package it.ginvault.produzione.dominio;

/** Cosa ha fatto il piano con una consegna (per i log del consumer). */
public enum EsitoConsegna {
    /** I kg disponibili sono cambiati (es. 12 -> 10,8): evento ResaRicalcolata. */
    RESA_RICALCOLATA,
    /** Consegna applicata, ma i kg disponibili sono gli stessi (es. consegna completa). */
    RESA_INVARIATA,
    /** Questa consegna (stesso ordine) era già stata applicata: ignorata. */
    GIA_REGISTRATA,
    /** Non è la botanica di stagione del piano (es. il ginepro di base): ignorata. */
    ALTRA_BOTANICA
}
