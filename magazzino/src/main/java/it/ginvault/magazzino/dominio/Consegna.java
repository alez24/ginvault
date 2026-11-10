package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Value object: la consegna di un ordine, come è arrivata. Colonne dentro la
 * tabella ordine_di_acquisto (tutte null finché l'ordine è APERTO).
 */
@Embeddable
public class Consegna {

    @Column(name = "consegnata_il")
    private LocalDate consegnataIl;

    @Column(name = "kg_arrivati", precision = 8, scale = 3)
    private BigDecimal kgArrivati;

    /** Da dove viene la botanica arrivata: l'origine del fornitore. */
    @Column(name = "origine", length = 100)
    private String origine;

    /** Il lotto di botanica creato da questa consegna. */
    @Column(name = "lotto_id")
    private UUID lottoId;

    @Column(name = "nota", length = 500)
    private String nota;

    protected Consegna() {
        // per JPA
    }

    Consegna(LocalDate consegnataIl, BigDecimal kgArrivati, String origine, UUID lottoId, String nota) {
        this.consegnataIl = consegnataIl;
        this.kgArrivati = kgArrivati;
        this.origine = origine;
        this.lottoId = lottoId;
        this.nota = nota;
    }

    public LocalDate consegnataIl() {
        return consegnataIl;
    }

    public BigDecimal kgArrivati() {
        return kgArrivati;
    }

    public String origine() {
        return origine;
    }

    public UUID lottoId() {
        return lottoId;
    }

    public String nota() {
        return nota;
    }
}
