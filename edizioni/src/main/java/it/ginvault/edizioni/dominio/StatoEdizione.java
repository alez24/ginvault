package it.ginvault.edizioni.dominio;

/**
 * In che fase di vendita è un'edizione. Non si salva: lo calcola l'Edizione
 * dall'orologio e dalle quote (vedi Edizione.stato), così non serve un giro
 * periodico che lo aggiorni allo scadere della prelazione.
 *
 *   IN_PRELAZIONE --(fine prelazione, o il distillatore apre la vendita)--> IN_VENDITA
 *   IN_VENDITA    --(quota ONLINE tutta prenotata)--> ESAURITA
 *   ESAURITA      --(la tiratura sale e ONLINE riceve bottiglie)--> IN_VENDITA
 *
 * La fase prima dell'annuncio (la formulazione in prova) è in Ricette: in
 * Edizioni un'edizione nasce già annunciata, e in prelazione.
 */
public enum StatoEdizione {
    /** Dall'annuncio, per la durata decisa dal Club: prenotano solo i soci, dalla quota SOCI. */
    IN_PRELAZIONE,
    /** Dopo la prelazione: soci e clienti prenotano dalla quota ONLINE. */
    IN_VENDITA,
    /** Dopo la prelazione, con la quota ONLINE tutta prenotata. */
    ESAURITA
}
