package it.ginvault.ricette.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Una prova di assaggio = una VERSIONE della formulazione, con le sue dosi.
 *
 * Entità interna all'aggregato {@link Formulazione}: si crea e si legge solo
 * passando dalla formulazione (costruttore non pubblico). Una volta creata non
 * cambia più: "ogni modifica crea una nuova versione, quelle vecchie non si
 * toccano". Lo garantiscono:
 *  - il codice: nessun metodo che modifichi le dosi, lista restituita in sola lettura;
 *  - la persistenza: @Immutable dice a Hibernate di non fare mai UPDATE su questa tabella.
 */
@Entity
@Table(name = "prova_di_assaggio")
@Immutable
public class ProvaDiAssaggio {

    // Id tecnico per il database: nel dominio una prova si identifica con la versione
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private int versione;

    @Column(name = "registrata_il", nullable = false, updatable = false)
    private Instant registrataIl;

    // Le dosi stanno nella tabella "dose", una riga per dose, nell'ordine dato
    @ElementCollection
    @CollectionTable(name = "dose", joinColumns = @JoinColumn(name = "prova_id", nullable = false))
    @OrderColumn(name = "posizione")
    private List<Dose> dosi = new ArrayList<>();

    /** Solo per JPA. */
    protected ProvaDiAssaggio() {
    }

    ProvaDiAssaggio(int versione, List<Dose> dosi, Instant registrataIl) {
        this.versione = versione;
        this.dosi = new ArrayList<>(dosi);   // copia: chi ha passato la lista non può più cambiarla
        this.registrataIl = registrataIl;
    }

    public int versione() {
        return versione;
    }

    public Instant registrataIl() {
        return registrataIl;
    }

    public List<Dose> dosi() {
        return Collections.unmodifiableList(dosi);
    }

    public Optional<Dose> doseDi(String botanica) {
        String cercata = Dose.normalizzaBotanica(botanica);
        return dosi.stream().filter(dose -> dose.botanica().equals(cercata)).findFirst();
    }
}
