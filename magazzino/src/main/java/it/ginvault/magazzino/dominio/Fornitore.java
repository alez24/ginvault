package it.ginvault.magazzino.dominio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Aggregate root: un fornitore di botaniche e lo storico delle sue consegne.
 * Lo storico serve a Elena per scegliere a chi ordinare: quante consegne sono
 * arrivate puntuali, quante in ritardo, quante ridotte.
 *
 * Semplificazione: ogni fornitore ha UNA origine (dove raccoglie), che diventa
 * l'origine dei lotti che consegna. Così l'origine non si scrive a mano a ogni
 * consegna, e non può avere errori di battitura: è il dato che Produzione
 * confronta con l'origine richiesta dall'edizione.
 */
@Entity
@Table(name = "fornitore")
public class Fornitore {

    @Id
    private UUID id;

    @Column(name = "nome", nullable = false, length = 100)
    private String nome;

    @Column(name = "origine", nullable = false, length = 100)
    private String origine;

    /**
     * Una riga per consegna nella tabella fornitore_storico. @OrderColumn
     * (posizione) tiene l'ordine di arrivo e fa sì che una consegna nuova sia
     * un solo INSERT, invece di cancellare e riscrivere tutto lo storico.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "fornitore_storico", joinColumns = @JoinColumn(name = "fornitore_id", nullable = false))
    @OrderColumn(name = "posizione")
    private List<VoceStorico> storico = new ArrayList<>();

    @Version
    private Long revisione;

    protected Fornitore() {
        // per JPA
    }

    private Fornitore(UUID id, String nome, String origine) {
        this.id = id;
        this.nome = nome;
        this.origine = origine;
    }

    public static Fornitore nuovo(UUID id, String nome, String origine) {
        if (nome == null || nome.isBlank() || origine == null || origine.isBlank()) {
            throw new OrdineNonValido("Un fornitore ha un nome e un'origine");
        }
        return new Fornitore(id, Botaniche.spaziSingoli(nome), Botaniche.spaziSingoli(origine));
    }

    /** Aggiunge allo storico la consegna di un suo ordine (già registrata sull'ordine). */
    public void annotaConsegna(OrdineDiAcquisto ordine) {
        if (!id.equals(ordine.fornitoreId())) {
            throw new IllegalArgumentException("L'ordine " + ordine.id() + " non è di questo fornitore");
        }
        Consegna consegna = ordine.consegna();
        if (consegna == null) {
            throw new IllegalArgumentException("L'ordine " + ordine.id() + " non è ancora stato consegnato");
        }
        storico.add(new VoceStorico(ordine.id(), ordine.botanica(), ordine.kgOrdinati(), consegna.kgArrivati(),
                ordine.consegnaAttesa(), consegna.consegnataIl(), consegna.nota()));
    }

    /** Quante consegne, e come sono andate. Una consegna può essere sia in ritardo sia ridotta. */
    public Riepilogo riepilogo() {
        int inRitardo = (int) storico.stream().filter(VoceStorico::inRitardo).count();
        int ridotte = (int) storico.stream().filter(VoceStorico::ridotta).count();
        return new Riepilogo(storico.size(), storico.size() - inRitardo, inRitardo, ridotte);
    }

    public UUID id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public String origine() {
        return origine;
    }

    /** Le consegne in ordine di registrazione, non modificabili. */
    public List<VoceStorico> storico() {
        return Collections.unmodifiableList(storico);
    }

    /**
     * @param puntuali consegnate entro la data attesa
     * @param ridotte  arrivati meno kg di quelli ordinati
     */
    public record Riepilogo(int consegne, int puntuali, int inRitardo, int ridotte) {
    }
}
