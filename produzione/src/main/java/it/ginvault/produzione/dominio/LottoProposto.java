package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un lotto del magazzino come lo vede Produzione: arriva da Giacenze.Disponibilita
 * (gRPC). Elena lo propone, Marco prova a integrarlo nel piano.
 *
 * @param kgDisponibili i kg del lotto non ancora riservati da nessun piano
 * @param fornitore     il nome, per mostrarlo (può mancare)
 */
public record LottoProposto(UUID lottoId, String botanica, String origine, BigDecimal kg, BigDecimal kgDisponibili,
                            LocalDate scadenza, String fornitore) {
}
