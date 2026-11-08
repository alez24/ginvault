package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Aggregate root: un lotto di botanica in magazzino, con la sua tracciabilità
 * (da dove viene, da quale fornitore e ordine, quando scade, se ha passato il
 * controllo qualità).
 *
 * Nasce da una consegna (OrdineDiAcquisto.registraConsegna) oppure senza
 * ordine, come il lotto dei campi dell'azienda (DatiIniziali).
 *
 * Rete di sicurezza: UNIQUE su ordine_id. Anche con due richieste nello stesso
 * istante, un ordine non genera due lotti.
 */
@Entity
@Table(name = "lotto_di_botanica",
        uniqueConstraints = @UniqueConstraint(name = "uk_lotto_ordine", columnNames = "ordine_id"),
        indexes = @Index(name = "ix_lotto_botanica", columnList = "botanica"))
public class LottoDiBotanica {

    @Id
    private UUID id;

    @Column(name = "botanica", nullable = false, length = 50)
    private String botanica;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    @Column(name = "kg", nullable = false, precision = 8, scale = 3)
    private BigDecimal kg;

    @Column(name = "scadenza", nullable = false)
    private LocalDate scadenza;

    @Enumerated(EnumType.STRING)
    @Column(name = "esito_qualita", nullable = false, length = 15)
    private EsitoQualita esitoQualita;

    @Column(name = "fornitore_id", nullable = false)
    private UUID fornitoreId;

    /** null per i lotti che non vengono da un ordine (es. i campi dell'azienda). */
    @Column(name = "ordine_id")
    private UUID ordineId;

    @Column(name = "ricevuto_il", nullable = false)
    private LocalDate ricevutoIl;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    @Version
    private Long revisione;

    protected LottoDiBotanica() {
        // per JPA
    }

    private LottoDiBotanica(UUID id, String botanica, String origine, BigDecimal kg, LocalDate scadenza,
                            EsitoQualita esitoQualita, UUID fornitoreId, UUID ordineId, LocalDate ricevutoIl,
                            Instant adesso) {
        this.id = id;
        this.botanica = botanica;
        this.origine = origine;
        this.kg = kg;
        this.scadenza = scadenza;
        this.esitoQualita = esitoQualita;
        this.fornitoreId = fornitoreId;
        this.ordineId = ordineId;
        this.ricevutoIl = ricevutoIl;
        this.creatoIl = adesso;
    }

    /** Dalla consegna di un ordine: i valori li ha già controllati l'ordine. */
    static LottoDiBotanica daConsegna(OrdineDiAcquisto ordine, String origine, BigDecimal kg, LocalDate scadenza,
                                      EsitoQualita esito, LocalDate consegnataIl, Instant adesso) {
        return new LottoDiBotanica(UUID.randomUUID(), ordine.botanica(), origine, kg, scadenza, esito,
                ordine.fornitoreId(), ordine.id(), consegnataIl, adesso);
    }

    /** Un lotto senza ordine (es. dai campi dell'azienda), con un id scelto da chi lo crea. */
    public static LottoDiBotanica senzaOrdine(UUID id, String botanica, String origine, BigDecimal kg,
                                              LocalDate scadenza, EsitoQualita esito, UUID fornitoreId,
                                              LocalDate ricevutoIl, Instant adesso) {
        if (origine == null || origine.isBlank()) {
            throw new OrdineNonValido("Serve l'origine del lotto");
        }
        if (scadenza == null || ricevutoIl == null || !scadenza.isAfter(ricevutoIl)) {
            throw new OrdineNonValido("La scadenza del lotto deve venire dopo la data di arrivo");
        }
        return new LottoDiBotanica(id, Botaniche.normalizza(botanica), Botaniche.spaziSingoli(origine),
                Chilogrammi.verifica(kg, "I kg del lotto", OrdineNonValido::new), scadenza, esito, fornitoreId,
                null, ricevutoIl, adesso);
    }

    /** Si può usare per produrre: controllo qualità superato e non scaduto. */
    public boolean utilizzabile(LocalDate oggi) {
        return esitoQualita == EsitoQualita.CONFORME && !scadenza.isBefore(oggi);
    }

    public UUID id() {
        return id;
    }

    public String botanica() {
        return botanica;
    }

    public String origine() {
        return origine;
    }

    public BigDecimal kg() {
        return kg;
    }

    public LocalDate scadenza() {
        return scadenza;
    }

    public EsitoQualita esitoQualita() {
        return esitoQualita;
    }

    public UUID fornitoreId() {
        return fornitoreId;
    }

    public UUID ordineId() {
        return ordineId;
    }

    public LocalDate ricevutoIl() {
        return ricevutoIl;
    }
}
