package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Un lotto del magazzino integrato nel piano dopo una consegna scarsa: i suoi
 * kg sono riservati per l'edizione (Giacenze.RiservaLotto) e contano tra gli
 * utilizzabili. Ha sempre l'origine richiesta: un lotto di un'altra origine
 * non arriva fin qui. Una riga della tabella piano_integrazione.
 */
@Embeddable
public class IntegrazioneNelPiano {

    @Column(name = "lotto_id", nullable = false)
    private UUID lottoId;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    @Column(name = "kg", nullable = false, precision = 8, scale = 3)
    private BigDecimal kg;

    /** Es. "integrazione: 1,2 kg dal lotto ... (Alta Val Varaita (Alpi Cozie))". */
    @Column(name = "motivo", nullable = false, length = 300)
    private String motivo;

    @Column(name = "integrata_il", nullable = false)
    private Instant integrataIl;

    protected IntegrazioneNelPiano() {
        // per JPA
    }

    IntegrazioneNelPiano(UUID lottoId, String origine, BigDecimal kg, String motivo, Instant integrataIl) {
        this.lottoId = lottoId;
        this.origine = origine;
        this.kg = kg;
        this.motivo = motivo;
        this.integrataIl = integrataIl;
    }

    public UUID lottoId() {
        return lottoId;
    }

    public String origine() {
        return origine;
    }

    public BigDecimal kg() {
        return kg;
    }

    public String motivo() {
        return motivo;
    }

    public Instant integrataIl() {
        return integrataIl;
    }
}
