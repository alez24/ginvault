package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value Object: quante bottiglie ha un'edizione. Sempre tra 1 e 600.
 *
 *   tiratura = min(600, grammi di botanica disponibili / grammi per bottiglia)
 *
 * arrotondata per difetto: una bottiglia a metà non si vende. Con 20 g di
 * lavanda per bottiglia: 12 kg -> 600, 10,8 kg -> 540, 20 kg -> 600 (il tetto).
 */
@Embeddable
public record Tiratura(@Column(name = "tiratura", nullable = false) int bottiglie) {

    /** Tetto di ogni edizione: oltre non si va, anche con più botanica. */
    public static final int MASSIMA = 600;

    public Tiratura {
        if (bottiglie < 1 || bottiglie > MASSIMA) {
            throw new EdizioneNonValida("La tiratura va da 1 a " + MASSIMA + " bottiglie, non " + bottiglie);
        }
    }

    /**
     * @throws EdizioneNonValida se con quei kg non si riempie nemmeno una bottiglia
     */
    public static Tiratura da(BigDecimal kgDisponibili, BigDecimal grammiPerBottiglia) {
        int possibili = bottigliePossibili(kgDisponibili, grammiPerBottiglia);
        if (possibili == 0) {
            throw new EdizioneNonValida("Con " + kgDisponibili.stripTrailingZeros().toPlainString()
                    + " kg di botanica di stagione non si riempie nemmeno una bottiglia: ne servono "
                    + grammiPerBottiglia.stripTrailingZeros().toPlainString() + " g per bottiglia");
        }
        return new Tiratura(possibili);
    }

    /** min(600, grammi / grammi per bottiglia), per difetto: anche 0, a differenza di una Tiratura. */
    public static int bottigliePossibili(BigDecimal kgDisponibili, BigDecimal grammiPerBottiglia) {
        BigDecimal possibili = kgDisponibili.movePointRight(3)          // kg -> grammi
                .divide(grammiPerBottiglia, 0, RoundingMode.DOWN);       // bottiglie intere
        return possibili.max(BigDecimal.ZERO).min(BigDecimal.valueOf(MASSIMA)).intValueExact();
    }

    public boolean minoreDi(Tiratura altra) {
        return bottiglie < altra.bottiglie;
    }
}
