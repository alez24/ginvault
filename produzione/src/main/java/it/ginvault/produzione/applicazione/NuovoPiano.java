package it.ginvault.produzione.applicazione;

import java.math.BigDecimal;
import java.util.UUID;

/** I dati di un'edizione appena creata che servono al piano (dall'evento EdizioneCreata). */
public record NuovoPiano(UUID edizioneId, UUID formulazioneId, int formulazioneVersione, String botanica,
                         BigDecimal grammiPerBottiglia, String origineRichiesta, BigDecimal kgPrevisti,
                         int tiraturaPrevista) {
}
