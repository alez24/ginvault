package it.ginvault.edizioni.dominio;

/**
 * Canali di vendita di un'edizione: ognuno riceve una quota di bottiglie.
 * Se la tiratura cala si toglie prima da ONLINE, poi da BAR, mai da SOCI
 * (vedi Edizione.ricalcola).
 */
public enum Canale {
    SOCI,
    BAR,
    ONLINE
}
