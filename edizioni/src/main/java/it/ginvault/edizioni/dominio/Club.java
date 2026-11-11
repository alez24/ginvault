package it.ginvault.edizioni.dominio;

import java.time.Duration;

/**
 * Il Club dei soci (bounded context "Esperienze & Club"), visto da Edizioni.
 *
 * Nel piano il Club è un mock dentro Edizioni (docs/piano.md, sezione 2):
 * l'implementazione è infrastruttura/club/ClubPreimpostato, con dati fissi.
 * Chi è socio non lo dice il Club: lo dice il ruolo SOCIO nel JWT.
 */
public interface Club {

    /** Quanto dura la prelazione dei soci dopo l'annuncio di un'edizione. */
    Duration durataPrelazione();

    /** Quante bottiglie di un'edizione può prenotare ogni socio in prelazione. */
    int bottigliePerSocio();

    /** Dove e quando i soci e i clienti ritirano le bottiglie di un'edizione. */
    Ritiro ritiroPer(Edizione edizione);
}
