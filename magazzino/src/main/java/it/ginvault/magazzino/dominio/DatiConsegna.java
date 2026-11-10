package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Quello che Elena scrive quando arriva la botanica. Le regole le controlla
 * OrdineDiAcquisto.registraConsegna, non questo record.
 *
 * @param nota facoltativa, per lo storico del fornitore (es. "pioggia durante la fioritura")
 */
public record DatiConsegna(BigDecimal kgArrivati, LocalDate consegnataIl, LocalDate scadenza,
                           EsitoQualita esitoQualita, String nota) {
}
