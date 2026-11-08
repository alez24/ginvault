package it.ginvault.magazzino.dominio;

/**
 * APERTO: ordinato, in attesa della consegna.
 * CONSEGNATO: la consegna è arrivata, anche se ridotta. Il resto non arriverà
 * (la pioggia ha rovinato la fioritura): l'ordine è chiuso.
 */
public enum StatoOrdine {
    APERTO,
    CONSEGNATO
}
