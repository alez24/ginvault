package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.util.List;

import it.ginvault.edizioni.dominio.RegoleDiVendita;

/**
 * Le regole di vendita come le legge il front-end (JSON):
 *   {"giorniDiPrelazione": 7, "bottigliePerSocio": 2, "bottigliePerCliente": 6,
 *    "prezzoSoci": 45.00, "prezzoOnline": 52.00, "minutiPerPagare": 15,
 *    "metodiDiPagamento": ["pm_card_visa", "pm_card_chargeDeclined"]}
 */
public record RegoleDiVenditaVista(long giorniDiPrelazione, int bottigliePerSocio, int bottigliePerCliente,
                                   BigDecimal prezzoSoci, BigDecimal prezzoOnline, long minutiPerPagare,
                                   List<String> metodiDiPagamento) {

    static RegoleDiVenditaVista di(RegoleDiVendita regole, MetodiDiPagamento metodi) {
        return new RegoleDiVenditaVista(regole.durataPrelazione().toDays(), regole.bottigliePerSocio(),
                regole.bottigliePerCliente(), regole.prezzoSoci(), regole.prezzoOnline(),
                regole.tempoPerPagare().toMinutes(), metodi.accettati());
    }
}
