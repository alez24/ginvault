package it.ginvault.edizioni.dominio;

import java.time.OffsetDateTime;

/**
 * Value Object: dove e quando si ritirano le bottiglie di un'edizione. È
 * un'esperienza del Club (es. la masterclass d'agosto): la decide il Club,
 * Edizioni la mostra con la prenotazione.
 *
 * @param occasione in minuscolo, si legge dopo «Ritiro alla»: "masterclass d’agosto"
 * @param inizio    con il fuso di Roma, es. 2026-08-22T18:00+02:00
 */
public record Ritiro(String occasione, String tema, String descrizione, OffsetDateTime inizio, OffsetDateTime fine,
                     String luogo, String daPortare) {
}
