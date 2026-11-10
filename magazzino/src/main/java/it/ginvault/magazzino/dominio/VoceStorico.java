package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value object: una consegna nello storico del fornitore. Copia i dati
 * dell'ordine al momento della consegna, così lo storico resta leggibile
 * da solo (una riga della tabella fornitore_storico).
 */
@Embeddable
public class VoceStorico {

    @Column(name = "ordine_id", nullable = false)
    private UUID ordineId;

    @Column(name = "botanica", nullable = false, length = 50)
    private String botanica;

    @Column(name = "kg_ordinati", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgOrdinati;

    @Column(name = "kg_arrivati", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgArrivati;

    @Column(name = "consegna_attesa", nullable = false)
    private LocalDate consegnaAttesa;

    @Column(name = "consegnata_il", nullable = false)
    private LocalDate consegnataIl;

    @Column(name = "nota", length = 500)
    private String nota;

    protected VoceStorico() {
        // per JPA
    }

    VoceStorico(UUID ordineId, String botanica, BigDecimal kgOrdinati, BigDecimal kgArrivati,
                LocalDate consegnaAttesa, LocalDate consegnataIl, String nota) {
        this.ordineId = ordineId;
        this.botanica = botanica;
        this.kgOrdinati = kgOrdinati;
        this.kgArrivati = kgArrivati;
        this.consegnaAttesa = consegnaAttesa;
        this.consegnataIl = consegnataIl;
        this.nota = nota;
    }

    public boolean ridotta() {
        return kgArrivati.compareTo(kgOrdinati) < 0;
    }

    public boolean inRitardo() {
        return giorniDiRitardo() > 0;
    }

    public long giorniDiRitardo() {
        return Math.max(0, ChronoUnit.DAYS.between(consegnaAttesa, consegnataIl));
    }

    public UUID ordineId() {
        return ordineId;
    }

    public String botanica() {
        return botanica;
    }

    public BigDecimal kgOrdinati() {
        return kgOrdinati;
    }

    public BigDecimal kgArrivati() {
        return kgArrivati;
    }

    public LocalDate consegnaAttesa() {
        return consegnaAttesa;
    }

    public LocalDate consegnataIl() {
        return consegnataIl;
    }

    public String nota() {
        return nota;
    }
}
