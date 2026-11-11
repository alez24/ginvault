package it.ginvault.edizioni.applicazione;

import java.util.List;

/**
 * Le carte di prova di Stripe che si possono scegliere dal menu
 * (application.yml, edizioni.vendita.metodi-di-pagamento): pm_card_visa
 * riesce, pm_card_chargeDeclined è rifiutata. Un altro metodo -> 400, prima
 * di mandare qualunque comando a Pagamenti.
 */
public record MetodiDiPagamento(List<String> accettati) {

    public MetodiDiPagamento {
        if (accettati == null || accettati.isEmpty()) {
            throw new IllegalArgumentException("Serve almeno un metodo di pagamento");
        }
        accettati = accettati.stream().map(String::trim).toList();
    }

    public boolean accetta(String metodo) {
        return metodo != null && accettati.contains(metodo);
    }
}
