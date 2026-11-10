package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento di dominio: è arrivata la botanica di un ordine, anche meno del
 * previsto (es. 10,8 kg di lavanda su 12).
 *
 * Lo registra l'OrdineDiAcquisto; Spring Data lo pubblica dentro il processo
 * quando il servizio salva l'ordine, e ScritturaOutbox lo scrive nella tabella
 * outbox, nella STESSA transazione. Su Kafka (magazzino.eventi, chiave =
 * botanica) va con la forma descritta in docs/eventi.md, non con questi nomi Java.
 *
 * @param occorsoIl quando la consegna è stata registrata
 */
public record ConsegnaRegistrata(UUID ordineId, UUID edizioneId, UUID lottoId, UUID fornitoreId, String botanica,
                                 String origine, BigDecimal kgOrdinati, BigDecimal kgArrivati,
                                 EsitoQualita esitoQualita, LocalDate consegnataIl, Instant occorsoIl) {
}
