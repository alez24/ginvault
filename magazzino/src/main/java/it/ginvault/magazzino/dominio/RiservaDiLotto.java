package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Una parte di un lotto messa da parte per un piano di produzione (es. 1,2 kg
 * di lavanda per l'edizione d'estate). Una riga della tabella lotto_riserva.
 *
 * Fa parte dell'aggregato LottoDiBotanica: solo il lotto sa quanti kg ha
 * ancora liberi, quindi solo lui può accettare o rifiutare una riserva.
 */
@Embeddable
public class RiservaDiLotto {

    @Column(name = "piano_id", nullable = false)
    private UUID pianoId;

    @Column(name = "kg", nullable = false, precision = 8, scale = 3)
    private BigDecimal kg;

    /** Chi l'ha chiesta: il "sub" del JWT (Marco, attraverso Produzione). */
    @Column(name = "riservata_da", nullable = false, length = 200)
    private String riservataDa;

    @Column(name = "riservata_il", nullable = false)
    private Instant riservataIl;

    protected RiservaDiLotto() {
        // per JPA
    }

    RiservaDiLotto(UUID pianoId, BigDecimal kg, String riservataDa, Instant riservataIl) {
        this.pianoId = pianoId;
        this.kg = kg;
        this.riservataDa = riservataDa;
        this.riservataIl = riservataIl;
    }

    public UUID pianoId() {
        return pianoId;
    }

    public BigDecimal kg() {
        return kg;
    }

    public String riservataDa() {
        return riservataDa;
    }

    public Instant riservataIl() {
        return riservataIl;
    }
}
