package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Value Object: le regole con cui si vendono le edizioni. Le prime due
 * vengono dal Club (vedi Club), le altre dalla configurazione di Edizioni
 * (application.yml, edizioni.vendita.*).
 *
 * @param durataPrelazione    quanto dura la prelazione dei soci dopo l'annuncio
 * @param bottigliePerSocio   quante bottiglie di un'edizione prenota al massimo un socio in prelazione
 * @param bottigliePerCliente quante bottiglie di un'edizione prenota al massimo una persona online
 * @param prezzoSoci          il prezzo di una bottiglia in prelazione (euro)
 * @param prezzoOnline        il prezzo di una bottiglia online (euro)
 * @param tempoPerPagare      quanto resta valida una prenotazione non ancora pagata
 */
public record RegoleDiVendita(Duration durataPrelazione, int bottigliePerSocio, int bottigliePerCliente,
                              BigDecimal prezzoSoci, BigDecimal prezzoOnline, Duration tempoPerPagare) {

    public RegoleDiVendita {
        if (durataPrelazione == null || durataPrelazione.isNegative()) {
            throw new IllegalArgumentException("Durata della prelazione non valida: " + durataPrelazione);
        }
        if (bottigliePerSocio < 1 || bottigliePerCliente < 1) {
            throw new IllegalArgumentException("Ogni persona deve poter prenotare almeno una bottiglia");
        }
        prezzoSoci = prezzo(prezzoSoci);
        prezzoOnline = prezzo(prezzoOnline);
        if (tempoPerPagare == null || tempoPerPagare.isNegative() || tempoPerPagare.isZero()) {
            throw new IllegalArgumentException("Tempo per pagare non valido: " + tempoPerPagare);
        }
    }

    private static BigDecimal prezzo(BigDecimal euro) {
        if (euro == null || euro.signum() <= 0) {
            throw new IllegalArgumentException("Prezzo non valido: " + euro);
        }
        // Al centesimo: 45 -> 45.00; 45.001 -> errore (non si arrotonda un prezzo)
        return euro.setScale(2, RoundingMode.UNNECESSARY);
    }
}
