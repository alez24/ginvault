package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Function;

/** Regola comune sui pesi: maggiori di zero, al grammo (3 decimali). Come in Edizioni. */
final class Chilogrammi {

    /** Il massimo che sta nella colonna numeric(8,3): 99.999,999 kg. */
    private static final BigDecimal MASSIMO = new BigDecimal("99999.999");

    private Chilogrammi() {
    }

    /**
     * @param cosa   per il messaggio, es. "I kg ordinati"
     * @param errore l'eccezione di dominio da lanciare (422)
     * @return i kg con 3 decimali (12 -> 12.000)
     */
    static BigDecimal verifica(BigDecimal kg, String cosa, Function<String, ? extends RuntimeException> errore) {
        if (kg == null || kg.signum() <= 0) {
            throw errore.apply(cosa + " devono essere maggiori di zero");
        }
        if (kg.stripTrailingZeros().scale() > 3) {
            throw errore.apply(cosa + " si indicano al grammo (al massimo 3 decimali)");
        }
        if (kg.compareTo(MASSIMO) > 0) {
            throw errore.apply(cosa + " sono troppi: al massimo " + MASSIMO.toPlainString());
        }
        return kg.setScale(3, RoundingMode.UNNECESSARY);
    }

    /** 10.800 -> "10,8": per i messaggi in italiano. */
    static String testo(BigDecimal kg) {
        return kg.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
