package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;

import it.ginvault.edizioni.dominio.RegoleDiVendita;

/**
 * Le regole di vendita come le legge il front-end (JSON):
 *   {"giorniDiPrelazione": 7, "bottigliePerSocio": 2, "bottigliePerCliente": 6,
 *    "prezzoSoci": 45.00, "prezzoOnline": 52.00, "minutiPerPagare": 15}
 */
public record RegoleDiVenditaVista(long giorniDiPrelazione, int bottigliePerSocio, int bottigliePerCliente,
                                   BigDecimal prezzoSoci, BigDecimal prezzoOnline, long minutiPerPagare) {

    static RegoleDiVenditaVista di(RegoleDiVendita regole) {
        return new RegoleDiVenditaVista(regole.durataPrelazione().toDays(), regole.bottigliePerSocio(),
                regole.bottigliePerCliente(), regole.prezzoSoci(), regole.prezzoOnline(),
                regole.tempoPerPagare().toMinutes());
    }
}
