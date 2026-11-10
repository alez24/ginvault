package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Regola comune sui pesi: maggiori di zero, al grammo (3 decimali). Come in Edizioni e Magazzino. */
final class Chilogrammi {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(3);

    /** Il massimo che sta nella colonna numeric(8,3): 99.999,999 kg. */
    private static final BigDecimal MASSIMO = new BigDecimal("99999.999");

    private Chilogrammi() {
    }

    /**
     * @param cosa per il messaggio, es. "I kg previsti"
     * @return i kg con 3 decimali (12 -> 12.000)
     */
    static BigDecimal verifica(BigDecimal kg, String cosa) {
        if (kg == null || kg.signum() <= 0) {
            throw new PianoNonValido(cosa + " devono essere maggiori di zero");
        }
        if (kg.stripTrailingZeros().scale() > 3) {
            throw new PianoNonValido(cosa + " si indicano al grammo (al massimo 3 decimali)");
        }
        if (kg.compareTo(MASSIMO) > 0) {
            throw new PianoNonValido(cosa + " sono troppi: al massimo " + MASSIMO.toPlainString());
        }
        return kg.setScale(3, RoundingMode.UNNECESSARY);
    }

    /** 10.800 -> "10,8": per i messaggi in italiano. */
    static String testo(BigDecimal kg) {
        return kg.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
