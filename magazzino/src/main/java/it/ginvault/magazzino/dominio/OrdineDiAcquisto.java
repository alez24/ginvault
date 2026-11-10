package it.ginvault.magazzino.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Aggregate root: un ordine di acquisto di una botanica a un fornitore, per
 * un'edizione (es. 12 kg di lavanda per l'edizione d'estate, da consegnare
 * dopo la raccolta di luglio).
 *
 * Dell'edizione conserva solo l'id: Magazzino non chiama Edizioni. A collegare
 * consegna ed edizione ci pensa Produzione, che legge l'evento ConsegnaRegistrata.
 *
 * Regole della consegna (registraConsegna):
 *  - UNA consegna per ordine: quella che arriva chiude l'ordine, anche se
 *    ridotta (il resto non arriverà);
 *  - i kg arrivati sono più di zero e non più di quelli ordinati;
 *  - la data di consegna non è nel futuro; la scadenza viene dopo la consegna;
 *  - ridotta = arrivati meno kg di quelli ordinati; in ritardo = consegnata
 *    dopo la data attesa. Una consegna può essere entrambe le cose;
 *  - la consegna crea il lotto di botanica e registra l'evento ConsegnaRegistrata.
 */
@Entity
@Table(name = "ordine_di_acquisto")
public class OrdineDiAcquisto {

    @Id
    private UUID id;

    @Column(name = "fornitore_id", nullable = false)
    private UUID fornitoreId;

    @Column(name = "botanica", nullable = false, length = 50)
    private String botanica;

    @Column(name = "kg_ordinati", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgOrdinati;

    @Column(name = "consegna_attesa", nullable = false)
    private LocalDate consegnaAttesa;

    @Column(name = "edizione_id", nullable = false)
    private UUID edizioneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stato", nullable = false, length = 12)
    private StatoOrdine stato;

    /** null finché la botanica non è arrivata. */
    @Embedded
    private Consegna consegna;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    /** Blocco ottimistico: due consegne nello stesso istante -> la seconda riceve 409. */
    @Version
    private Long revisione;

    /** Eventi di dominio in attesa di pubblicazione: non vanno nel database. */
    @Transient
    private final List<Object> eventi = new ArrayList<>();

    protected OrdineDiAcquisto() {
        // per JPA
    }

    private OrdineDiAcquisto(UUID fornitoreId, String botanica, BigDecimal kgOrdinati, LocalDate consegnaAttesa,
                             UUID edizioneId, Instant adesso) {
        this.id = UUID.randomUUID();
        this.fornitoreId = fornitoreId;
        this.botanica = botanica;
        this.kgOrdinati = kgOrdinati;
        this.consegnaAttesa = consegnaAttesa;
        this.edizioneId = edizioneId;
        this.stato = StatoOrdine.APERTO;
        this.creatoIl = adesso;
    }

    /**
     * Factory: un ordine APERTO. L'esistenza del fornitore la controlla il
     * servizio applicativo (è un altro aggregato).
     */
    public static OrdineDiAcquisto nuovo(UUID fornitoreId, String botanica, BigDecimal kgOrdinati,
                                         LocalDate consegnaAttesa, UUID edizioneId, Instant adesso) {
        if (fornitoreId == null) {
            throw new OrdineNonValido("Serve il fornitore");
        }
        if (edizioneId == null) {
            throw new OrdineNonValido("Serve l'edizione per cui si ordina la botanica");
        }
        if (consegnaAttesa == null) {
            throw new OrdineNonValido("Serve la data di consegna attesa");
        }
        BigDecimal kg = Chilogrammi.verifica(kgOrdinati, "I kg ordinati", OrdineNonValido::new);
        return new OrdineDiAcquisto(fornitoreId, Botaniche.normalizza(botanica), kg, consegnaAttesa, edizioneId,
                adesso);
    }

    // ---------------------------------------------------------------- comportamento

    /**
     * La botanica è arrivata, anche meno del previsto: chiude l'ordine e crea il
     * lotto. Tutto o niente: se una regola non torna, l'ordine resta APERTO.
     *
     * @param origine l'origine della botanica, quella del fornitore
     * @param oggi    la data di oggi (fuso di Roma): una consegna non può essere nel futuro
     * @return il nuovo lotto, ancora da salvare
     * @throws OrdineGiaConsegnato la consegna di questo ordine c'è già (-> 409)
     * @throws ConsegnaNonValida   kg, date o esito non validi (-> 422)
     */
    public LottoDiBotanica registraConsegna(DatiConsegna dati, String origine, LocalDate oggi, Instant adesso) {
        if (stato == StatoOrdine.CONSEGNATO) {
            throw new OrdineGiaConsegnato("L'ordine " + id + " è già stato consegnato il " + consegna.consegnataIl()
                    + " (" + Chilogrammi.testo(consegna.kgArrivati()) + " kg di " + Chilogrammi.testo(kgOrdinati)
                    + "): ogni ordine ha una sola consegna");
        }
        BigDecimal kg = Chilogrammi.verifica(dati.kgArrivati(), "I kg arrivati", ConsegnaNonValida::new);
        if (kg.compareTo(kgOrdinati) > 0) {
            throw new ConsegnaNonValida("Sono arrivati " + Chilogrammi.testo(kg) + " kg ma ne erano stati ordinati "
                    + Chilogrammi.testo(kgOrdinati) + ": i kg in più vanno registrati con un altro ordine");
        }
        LocalDate consegnataIl = dati.consegnataIl();
        if (consegnataIl == null) {
            throw new ConsegnaNonValida("Serve la data di consegna");
        }
        if (consegnataIl.isAfter(oggi)) {
            throw new ConsegnaNonValida("La data di consegna (" + consegnataIl + ") è nel futuro: si registra una"
                    + " consegna quando la botanica è arrivata");
        }
        if (dati.scadenza() == null || !dati.scadenza().isAfter(consegnataIl)) {
            throw new ConsegnaNonValida("La scadenza del lotto deve venire dopo la data di consegna");
        }
        if (dati.esitoQualita() == null) {
            throw new ConsegnaNonValida("Serve l'esito del controllo qualità (CONFORME o NON_CONFORME)");
        }
        String nota = dati.nota() == null || dati.nota().isBlank() ? null : Botaniche.spaziSingoli(dati.nota());

        // Da qui nessuna eccezione: lo stato cambia tutto insieme
        LottoDiBotanica lotto = LottoDiBotanica.daConsegna(this, origine, kg, dati.scadenza(),
                dati.esitoQualita(), consegnataIl, adesso);
        consegna = new Consegna(consegnataIl, kg, origine, lotto.id(), nota);
        stato = StatoOrdine.CONSEGNATO;
        eventi.add(new ConsegnaRegistrata(id, edizioneId, lotto.id(), fornitoreId, botanica, origine, kgOrdinati,
                kg, dati.esitoQualita(), consegnataIl, adesso));
        return lotto;
    }

    // ---------------------------------------------------------------- eventi di dominio

    /**
     * Gli eventi registrati e non ancora pubblicati: Spring Data li pubblica
     * quando il servizio chiama repository.save(ordine), poi chiama eventiPubblicati().
     * Li raccoglie ScritturaOutbox, che li salva nella tabella outbox.
     */
    @DomainEvents
    public List<Object> eventi() {
        return List.copyOf(eventi);
    }

    @AfterDomainEventPublication
    protected void eventiPubblicati() {
        eventi.clear();
    }

    // ---------------------------------------------------------------- lettura

    public UUID id() {
        return id;
    }

    public UUID fornitoreId() {
        return fornitoreId;
    }

    public String botanica() {
        return botanica;
    }

    public BigDecimal kgOrdinati() {
        return kgOrdinati;
    }

    public LocalDate consegnaAttesa() {
        return consegnaAttesa;
    }

    public UUID edizioneId() {
        return edizioneId;
    }

    public StatoOrdine stato() {
        return stato;
    }

    /** null se l'ordine è ancora APERTO. */
    public Consegna consegna() {
        return consegna;
    }

    public Instant creatoIl() {
        return creatoIl;
    }

    /** Arrivati meno kg di quelli ordinati (false se non è ancora arrivato niente). */
    public boolean ridotta() {
        return consegna != null && consegna.kgArrivati().compareTo(kgOrdinati) < 0;
    }

    /** I kg che non arriveranno: 12 - 10,8 = 1,2. Zero se la consegna è completa o non c'è. */
    public BigDecimal kgMancanti() {
        return consegna == null ? BigDecimal.ZERO.setScale(3) : kgOrdinati.subtract(consegna.kgArrivati());
    }

    /** Consegnata dopo la data attesa. */
    public boolean inRitardo() {
        return giorniDiRitardo() > 0;
    }

    public long giorniDiRitardo() {
        if (consegna == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(consegnaAttesa, consegna.consegnataIl()));
    }
}
