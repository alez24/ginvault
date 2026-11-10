package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;

/**
 * Il risultato di LottoDiBotanica.riserva.
 *
 * @param kgRiservati    i kg messi da parte per il piano
 * @param kgDisponibili  i kg del lotto ancora liberi, dopo la riserva
 * @param giaRiservato   true se la stessa riserva c'era già (richiesta ripetuta)
 */
public record EsitoRiserva(BigDecimal kgRiservati, BigDecimal kgDisponibili, boolean giaRiservato) {
}
