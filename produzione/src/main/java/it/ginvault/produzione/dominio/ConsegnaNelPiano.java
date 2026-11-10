package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Una consegna già applicata al piano: quanti kg erano attesi, quanti ne sono
 * arrivati e quanti si possono usare per l'edizione (zero se il lotto ha
 * un'altra origine o non ha passato il controllo qualità).
 * Una riga della tabella piano_consegna.
 */
@Embeddable
public class ConsegnaNelPiano {

    @Column(name = "ordine_id", nullable = false)
    private UUID ordineId;

    @Column(name = "lotto_id", nullable = false)
    private UUID lottoId;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    @Column(name = "kg_ordinati", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgOrdinati;

    @Column(name = "kg_arrivati", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgArrivati;

    @Column(name = "kg_utilizzabili", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgUtilizzabili;

    /** Es. "consegna ridotta: 10,8 kg su 12". */
    @Column(name = "motivo", nullable = false, length = 300)
    private String motivo;

    @Column(name = "consegnata_il", nullable = false)
    private LocalDate consegnataIl;

    protected ConsegnaNelPiano() {
        // per JPA
    }

    ConsegnaNelPiano(UUID ordineId, UUID lottoId, String origine, BigDecimal kgOrdinati, BigDecimal kgArrivati,
                     BigDecimal kgUtilizzabili, String motivo, LocalDate consegnataIl) {
        this.ordineId = ordineId;
        this.lottoId = lottoId;
        this.origine = origine;
        this.kgOrdinati = kgOrdinati;
        this.kgArrivati = kgArrivati;
        this.kgUtilizzabili = kgUtilizzabili;
        this.motivo = motivo;
        this.consegnataIl = consegnataIl;
    }

    public UUID ordineId() {
        return ordineId;
    }

    public UUID lottoId() {
        return lottoId;
    }

    public String origine() {
        return origine;
    }

    public BigDecimal kgOrdinati() {
        return kgOrdinati;
    }

    public BigDecimal kgArrivati() {
        return kgArrivati;
    }

    public BigDecimal kgUtilizzabili() {
        return kgUtilizzabili;
    }

    public String motivo() {
        return motivo;
    }

    public LocalDate consegnataIl() {
        return consegnataIl;
    }
}
