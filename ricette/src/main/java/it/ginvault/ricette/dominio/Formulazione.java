package it.ginvault.ricette.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * AGGREGATO Formulazione (radice): la ricetta di un gin, con le sue prove di
 * assaggio. È l'unico punto da cui si modificano le prove, e protegge le regole:
 *
 *  1. ogni prova contiene la botanica di stagione (serve a Edizioni per la tiratura)
 *     e non ripete due volte la stessa botanica;
 *  2. si aggiungono versioni solo finché la formulazione è IN_PROVA, e una
 *     versione già registrata non cambia mai (si crea la successiva);
 *  3. il panel sceglie UNA versione, una volta sola; le altre restano archiviate;
 *  4. una formulazione usata per un'edizione non si riusa mai.
 *
 * Fuori dal dominio si conosce solo l'id: Edizioni conserva un riferimento
 * (id + versione scelta), mai la formulazione.
 */
@Entity
@Table(name = "formulazione")
public class Formulazione {

    // Id generato dal dominio (non dal database): esiste già prima del salvataggio
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String nome;

    @Column(name = "botanica_di_stagione", nullable = false, length = 50)
    private String botanicaDiStagione;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatoFormulazione stato;

    @Column(name = "versione_scelta")
    private Integer versioneScelta;

    // L'edizione che ha usato la formulazione: solo l'id (database per service)
    @Column(name = "edizione_id")
    private UUID edizioneId;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "formulazione_id", nullable = false, updatable = false)
    @OrderBy("versione")
    private List<ProvaDiAssaggio> prove = new ArrayList<>();

    /**
     * Blocco ottimistico (NON è la versione della ricetta): se due richieste
     * modificano la stessa formulazione insieme (es. due edizioni che provano a
     * usarla), la seconda fallisce invece di sovrascrivere la prima.
     * Null = formulazione mai salvata.
     */
    @Version
    private Long revisione;

    /** Solo per JPA. */
    protected Formulazione() {
    }

    private Formulazione(UUID id, String nome, String botanicaDiStagione) {
        this.id = id;
        this.nome = nome;
        this.botanicaDiStagione = botanicaDiStagione;
        this.stato = StatoFormulazione.IN_PROVA;
    }

    /** Factory: una nuova formulazione nasce IN_PROVA con la sua prima prova (versione 1). */
    public static Formulazione nuova(String nome, String botanicaDiStagione, List<Dose> dosiPrimaProva) {
        String nomePulito = nome == null ? "" : nome.trim();
        if (nomePulito.isEmpty()) {
            throw new FormulazioneNonValida("Il nome della formulazione è obbligatorio");
        }
        String botanica = Dose.normalizzaBotanica(botanicaDiStagione);
        if (botanica.isEmpty()) {
            throw new FormulazioneNonValida("La botanica di stagione è obbligatoria");
        }
        Formulazione formulazione = new Formulazione(UUID.randomUUID(), nomePulito, botanica);
        formulazione.aggiungiProva(dosiPrimaProva);
        return formulazione;
    }

    // ------------------------------------------------------------------ comportamento

    /** Una nuova prova per il panel: diventa la versione successiva. Le precedenti restano com'erano. */
    public ProvaDiAssaggio nuovaVersione(List<Dose> dosi) {
        if (stato != StatoFormulazione.IN_PROVA) {
            throw new TransizioneNonConsentita("La formulazione è " + stato + " (versione scelta: "
                    + versioneScelta + "): non si aggiungono più versioni");
        }
        return aggiungiProva(dosi);
    }

    /** Il panel sceglie una versione: la formulazione diventa SCELTA, le altre prove restano archiviate. */
    public void scegli(int versione) {
        if (stato != StatoFormulazione.IN_PROVA) {
            throw new TransizioneNonConsentita("Il panel ha già scelto la versione " + versioneScelta
                    + " (formulazione " + stato + ")");
        }
        if (prova(versione).isEmpty()) {
            throw new FormulazioneNonValida("La versione " + versione + " non esiste: le prove vanno da 1 a "
                    + prove.size());
        }
        this.versioneScelta = versione;
        this.stato = StatoFormulazione.SCELTA;
    }

    /**
     * La formulazione viene usata per un'edizione e non si riusa mai più.
     * Ripetere la richiesta per la STESSA edizione non ha effetto (idempotente):
     * se Edizioni ritenta dopo un timeout non riceve un errore falso.
     */
    public void usaPerEdizione(UUID edizione) {
        if (edizione == null) {
            throw new FormulazioneNonValida("L'id dell'edizione è obbligatorio");
        }
        switch (stato) {
            case IN_PROVA -> throw new TransizioneNonConsentita(
                    "La formulazione non è ancora stata scelta dal panel: non si può usare per un'edizione");
            case USATA -> {
                if (edizione.equals(edizioneId)) {
                    return;
                }
                throw new TransizioneNonConsentita("La formulazione è già stata usata per l'edizione "
                        + edizioneId + ": una formulazione usata non si riusa mai");
            }
            case SCELTA -> {
                this.edizioneId = edizione;
                this.stato = StatoFormulazione.USATA;
            }
        }
    }

    /** Esito di una prova, ricavato dallo stato della formulazione. */
    public EsitoProva esitoDi(ProvaDiAssaggio prova) {
        if (stato == StatoFormulazione.IN_PROVA) {
            return EsitoProva.IN_ASSAGGIO;
        }
        return prova.versione() == versioneScelta ? EsitoProva.SCELTA : EsitoProva.ARCHIVIATA;
    }

    // ------------------------------------------------------------------ lettura

    public UUID id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public String botanicaDiStagione() {
        return botanicaDiStagione;
    }

    public StatoFormulazione stato() {
        return stato;
    }

    public List<ProvaDiAssaggio> prove() {
        return Collections.unmodifiableList(prove);
    }

    public Optional<ProvaDiAssaggio> prova(int versione) {
        return prove.stream().filter(prova -> prova.versione() == versione).findFirst();
    }

    public Optional<ProvaDiAssaggio> provaScelta() {
        return versioneScelta == null ? Optional.empty() : prova(versioneScelta);
    }

    public Optional<UUID> edizione() {
        return Optional.ofNullable(edizioneId);
    }

    // ------------------------------------------------------------------ regole interne

    private ProvaDiAssaggio aggiungiProva(List<Dose> dosi) {
        verificaDosi(dosi);
        ProvaDiAssaggio prova = new ProvaDiAssaggio(prove.size() + 1, dosi, Instant.now());
        prove.add(prova);
        return prova;
    }

    private void verificaDosi(List<Dose> dosi) {
        if (dosi == null || dosi.isEmpty()) {
            throw new FormulazioneNonValida("Una prova deve avere almeno una dose");
        }
        Set<String> botaniche = new HashSet<>();
        for (Dose dose : dosi) {
            if (dose == null) {
                throw new FormulazioneNonValida("Una dose non può essere vuota");
            }
            if (!botaniche.add(dose.botanica())) {
                throw new FormulazioneNonValida("La botanica " + dose.botanica()
                        + " compare due volte nella stessa prova");
            }
        }
        if (!botaniche.contains(botanicaDiStagione)) {
            throw new FormulazioneNonValida("Ogni prova deve contenere la botanica di stagione ("
                    + botanicaDiStagione + "): serve per calcolare la tiratura");
        }
    }
}
