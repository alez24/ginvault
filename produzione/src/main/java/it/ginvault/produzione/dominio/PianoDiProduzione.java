package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import it.ginvault.produzione.dominio.IntegrazioneRifiutata.Motivo;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Aggregate root: il piano di produzione di un'edizione. Nasce dall'evento
 * EdizioneCreata (Marco crea l'edizione d'estate: 12 kg di lavanda dell'Alta
 * Val Varaita, 20 g per bottiglia) e segue la botanica di stagione che arriva
 * davvero dal magazzino.
 *
 * Regole della consegna (registraConsegna, dall'evento ConsegnaRegistrata):
 *  - conta solo la botanica di stagione del piano: le altre si ignorano;
 *  - ogni ordine conta una volta sola (seconda difesa contro i doppioni,
 *    oltre agli eventId già elaborati);
 *  - i kg utilizzabili sono quelli arrivati, ma solo se il lotto è CONFORME e
 *    ha l'origine richiesta dall'edizione; altrimenti zero: l'edizione resta
 *    fedele alla sua origine;
 *  - kg disponibili = i kg previsti non ancora coperti da ordini consegnati
 *    con l'origine richiesta, più i kg utilizzabili arrivati, al massimo i previsti:
 *        min(previsti, max(0, previsti - Σ ordinati con l'origine richiesta) + Σ utilizzabili)
 *    Produzione vede un ordine solo quando arriva la sua consegna: gli ordini
 *    ancora aperti stanno nei kg previsti non coperti, cioè si aspettano ancora.
 *    La consegna di Elena:                     max(0, 12 - 12) + 10,8 = 10,8
 *    Due ordini da 6 kg, uno consegnato:       max(0, 12 - 6) + 6     = 12
 *    2 kg dai campi (altra origine) arrivati:  max(0, 12 - 0) + 0     = 12 (non tolgono niente)
 *    Un riordino di 1,2 kg dall'Alta Val Varaita, dopo Elena:  0 + 10,8 + 1,2 = 12
 *  - se i kg disponibili cambiano si registra l'evento ResaRicalcolata.
 *
 * Regole dell'integrazione (verificaProposta e integraLotto, da
 * POST /piani/{id}/integrazioni): dopo una consegna scarsa Marco prova a
 * coprire i kg mancanti con un lotto del magazzino proposto da Elena.
 *  - il lotto deve essere della botanica di stagione e con l'ORIGINE
 *    RICHIESTA: il lotto dei campi dell'azienda viene rifiutato, perché
 *    l'edizione deve restare fedele alla sua origine;
 *  - un lotto si integra una volta sola;
 *  - solo se mancano kg, e non più di quelli che mancano;
 *  - i kg integrati (riservati in magazzino) si sommano agli utilizzabili:
 *        10,8 kg arrivati + 1,2 kg integrati = 12 kg, di nuovo 600 bottiglie.
 */
@Entity
@Table(name = "piano_di_produzione",
        uniqueConstraints = @UniqueConstraint(name = "uk_piano_edizione", columnNames = "edizione_id"))
public class PianoDiProduzione {

    /** Come in Edizioni: al massimo 100 caratteri. */
    private static final int MASSIMO_ORIGINE = 100;

    @Id
    private UUID id;

    /** Un piano per edizione (UNIQUE): un secondo EdizioneCreata non ne crea un altro. */
    @Column(name = "edizione_id", nullable = false, updatable = false)
    private UUID edizioneId;

    @Column(name = "formulazione_id", nullable = false, updatable = false)
    private UUID formulazioneId;

    @Column(name = "formulazione_versione", nullable = false, updatable = false)
    private int formulazioneVersione;

    @Column(name = "botanica", nullable = false, length = 50, updatable = false)
    private String botanica;

    @Column(name = "grammi_per_bottiglia", nullable = false, precision = 7, scale = 2, updatable = false)
    private BigDecimal grammiPerBottiglia;

    /** Da dove deve venire la botanica di stagione: un lotto di un'altra origine non conta. */
    @Column(name = "origine_richiesta", nullable = false, length = MASSIMO_ORIGINE, updatable = false)
    private String origineRichiesta;

    /** I kg con cui è stata calcolata la tiratura dell'edizione. */
    @Column(name = "kg_previsti", nullable = false, precision = 8, scale = 3, updatable = false)
    private BigDecimal kgPrevisti;

    /** I kg su cui si può contare adesso, dopo le consegne. */
    @Column(name = "kg_disponibili", nullable = false, precision = 8, scale = 3)
    private BigDecimal kgDisponibili;

    /** La tiratura decisa da Edizioni alla creazione (600 bottiglie). */
    @Column(name = "tiratura_prevista", nullable = false, updatable = false)
    private int tiraturaPrevista;

    /** Le consegne applicate al piano, nell'ordine di arrivo. */
    @ElementCollection
    @CollectionTable(name = "piano_consegna", joinColumns = @JoinColumn(name = "piano_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_piano_consegna_ordine",
                    columnNames = {"piano_id", "ordine_id"}))
    @OrderColumn(name = "posizione")
    private List<ConsegnaNelPiano> consegne = new ArrayList<>();

    /** I lotti del magazzino integrati per coprire i kg mancanti, in ordine. */
    @ElementCollection
    @CollectionTable(name = "piano_integrazione", joinColumns = @JoinColumn(name = "piano_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_piano_integrazione_lotto",
                    columnNames = {"piano_id", "lotto_id"}))
    @OrderColumn(name = "posizione")
    private List<IntegrazioneNelPiano> integrazioni = new ArrayList<>();

    @Column(name = "creato_il", nullable = false, updatable = false)
    private Instant creatoIl;

    @Column(name = "aggiornato_il", nullable = false)
    private Instant aggiornatoIl;

    /** Blocco ottimistico: due consegne dello stesso piano in parallelo -> la seconda riprova. */
    @Version
    private Long revisione;

    @Transient
    private final List<Object> eventi = new ArrayList<>();

    protected PianoDiProduzione() {
        // per JPA
    }

    private PianoDiProduzione(UUID edizioneId, UUID formulazioneId, int formulazioneVersione, String botanica,
                              BigDecimal grammiPerBottiglia, String origineRichiesta, BigDecimal kgPrevisti,
                              int tiraturaPrevista, Instant adesso) {
        this.id = UUID.randomUUID();
        this.edizioneId = edizioneId;
        this.formulazioneId = formulazioneId;
        this.formulazioneVersione = formulazioneVersione;
        this.botanica = botanica;
        this.grammiPerBottiglia = grammiPerBottiglia;
        this.origineRichiesta = origineRichiesta;
        this.kgPrevisti = kgPrevisti;
        this.kgDisponibili = kgPrevisti;
        this.tiraturaPrevista = tiraturaPrevista;
        this.creatoIl = adesso;
        this.aggiornatoIl = adesso;
    }

    /**
     * Factory: il piano di un'edizione appena creata, con tutti i kg previsti
     * ancora disponibili.
     *
     * @throws PianoNonValido dati mancanti o fuori dalle regole (l'evento va nel dead letter topic)
     */
    public static PianoDiProduzione nuovo(UUID edizioneId, UUID formulazioneId, int formulazioneVersione,
                                          String botanica, BigDecimal grammiPerBottiglia, String origineRichiesta,
                                          BigDecimal kgPrevisti, int tiraturaPrevista, Instant adesso) {
        if (edizioneId == null || formulazioneId == null) {
            throw new PianoNonValido("Servono l'edizione e la formulazione");
        }
        if (formulazioneVersione < 1) {
            throw new PianoNonValido("La versione della formulazione parte da 1");
        }
        if (botanica == null || botanica.isBlank()) {
            throw new PianoNonValido("Serve la botanica di stagione");
        }
        if (grammiPerBottiglia == null || grammiPerBottiglia.signum() <= 0) {
            throw new PianoNonValido("I grammi per bottiglia devono essere maggiori di zero");
        }
        if (origineRichiesta == null || origineRichiesta.isBlank()) {
            throw new PianoNonValido("Serve l'origine richiesta per la botanica di stagione");
        }
        String origine = Origini.pulisci(origineRichiesta);
        if (origine.length() > MASSIMO_ORIGINE) {
            throw new PianoNonValido("L'origine può avere al massimo " + MASSIMO_ORIGINE + " caratteri");
        }
        BigDecimal kg = Chilogrammi.verifica(kgPrevisti, "I kg previsti");
        if (tiraturaPrevista < 1) {
            throw new PianoNonValido("La tiratura prevista deve essere di almeno una bottiglia");
        }
        return new PianoDiProduzione(edizioneId, formulazioneId, formulazioneVersione, Origini.botanica(botanica),
                grammiPerBottiglia, origine, kg, tiraturaPrevista, adesso);
    }

    // ---------------------------------------------------------------- comportamento

    /**
     * Una consegna registrata dal magazzino per questa edizione: ricalcola i kg
     * disponibili. Tutto o niente: se un dato non torna il piano non cambia.
     *
     * @return cosa è successo (per i log)
     * @throws PianoNonValido dati della consegna non validi
     */
    public EsitoConsegna registraConsegna(ConsegnaArrivata consegna, Instant adesso) {
        verifica(consegna);
        if (!Origini.botanica(consegna.botanica()).equals(botanica)) {
            return EsitoConsegna.ALTRA_BOTANICA;
        }
        if (consegne.stream().anyMatch(gia -> gia.ordineId().equals(consegna.ordineId()))) {
            return EsitoConsegna.GIA_REGISTRATA;
        }
        BigDecimal ordinati = Chilogrammi.verifica(consegna.kgOrdinati(), "I kg ordinati");
        BigDecimal arrivati = consegna.kgArrivati().setScale(3, RoundingMode.UNNECESSARY);
        String origine = Origini.pulisci(consegna.origine());
        boolean stessaOrigine = Origini.stessa(origine, origineRichiesta);
        BigDecimal utilizzabili = consegna.conforme() && stessaOrigine ? arrivati : Chilogrammi.ZERO;
        String motivo = motivo(consegna, ordinati, arrivati, origine, stessaOrigine);

        consegne.add(new ConsegnaNelPiano(consegna.ordineId(), consegna.lottoId(), origine, ordinati, arrivati,
                utilizzabili, motivo, consegna.consegnataIl()));
        aggiornatoIl = adesso;
        BigDecimal prima = kgDisponibili;
        BigDecimal dopo = ricalcolaDisponibili();
        if (dopo.compareTo(prima) == 0) {
            return EsitoConsegna.RESA_INVARIATA;
        }
        kgDisponibili = dopo;
        eventi.add(new ResaRicalcolata(id, edizioneId, botanica, kgPrevisti, prima, dopo, consegna.ordineId(),
                null, motivo, adesso));
        return EsitoConsegna.RESA_RICALCOLATA;
    }

    /**
     * Marco valuta un lotto proposto da Elena, PRIMA che il magazzino lo
     * riservi: un lotto rifiutato non si tocca nemmeno (vedi le regole in cima).
     *
     * @return i kg da riservare, al grammo
     * @throws IntegrazioneRifiutata con il motivo: altra botanica, origine diversa,
     *                               già integrato, kg non validi, piano completo, kg eccessivi
     */
    public BigDecimal verificaProposta(LottoProposto lotto, BigDecimal kgRichiesti) {
        verificaLotto(lotto);
        BigDecimal kg = kgDaIntegrare(kgRichiesti);
        BigDecimal mancanti = kgMancanti();
        if (mancanti.signum() == 0) {
            throw new IntegrazioneRifiutata(Motivo.PIANO_COMPLETO, "Il piano ha già tutti i "
                    + Chilogrammi.testo(kgPrevisti) + " kg previsti di " + botanica + ": non serve integrare");
        }
        if (kg.compareTo(mancanti) > 0) {
            throw new IntegrazioneRifiutata(Motivo.KG_ECCESSIVI, "Mancano " + Chilogrammi.testo(mancanti)
                    + " kg: non se ne possono integrare " + Chilogrammi.testo(kg),
                    Map.of("kgMancanti", mancanti));
        }
        return kg;
    }

    /**
     * Il lotto è riservato in magazzino: i suoi kg contano tra gli utilizzabili
     * e la resa si ricalcola. Le regole del lotto (botanica, origine) valgono
     * anche qui: il piano le protegge da solo, chiunque lo chiami.
     *
     * NON ricontrolla i kg mancanti: i kg sono già riservati, e se nel
     * frattempo è arrivata un'altra consegna il conto resta comunque al
     * massimo dei kg previsti.
     *
     * @return GIA_INTEGRATO se il lotto c'era già (richiesta ripetuta)
     */
    public EsitoIntegrazione integraLotto(LottoProposto lotto, BigDecimal kgRiservati, Instant adesso) {
        try {
            verificaLotto(lotto);
        } catch (IntegrazioneRifiutata rifiuto) {
            if (rifiuto.motivo() == Motivo.LOTTO_GIA_INTEGRATO) {
                return EsitoIntegrazione.GIA_INTEGRATO;
            }
            throw rifiuto;
        }
        BigDecimal kg = kgDaIntegrare(kgRiservati);
        String origine = Origini.pulisci(lotto.origine());
        String motivo = "integrazione: " + Chilogrammi.testo(kg) + " kg dal lotto " + lotto.lottoId()
                + " (" + origine + ")";
        integrazioni.add(new IntegrazioneNelPiano(lotto.lottoId(), origine, kg, motivo, adesso));
        aggiornatoIl = adesso;
        BigDecimal prima = kgDisponibili;
        BigDecimal dopo = ricalcolaDisponibili();
        if (dopo.compareTo(prima) == 0) {
            return EsitoIntegrazione.RESA_INVARIATA;
        }
        kgDisponibili = dopo;
        eventi.add(new ResaRicalcolata(id, edizioneId, botanica, kgPrevisti, prima, dopo, null, lotto.lottoId(),
                motivo, adesso));
        return EsitoIntegrazione.RESA_RICALCOLATA;
    }

    /** I kg che mancano per tornare ai previsti (1,2 dopo la consegna di Elena). */
    public BigDecimal kgMancanti() {
        return kgPrevisti.subtract(kgDisponibili);
    }

    /** Botanica, origine e lotto non ancora integrato. L'origine prima di tutto: è la regola dell'edizione. */
    private void verificaLotto(LottoProposto lotto) {
        if (lotto == null || lotto.lottoId() == null) {
            throw new IntegrazioneRifiutata(Motivo.LOTTO_NON_DISPONIBILE, "Serve il lotto da integrare");
        }
        if (lotto.botanica() == null || lotto.botanica().isBlank()
                || !Origini.botanica(lotto.botanica()).equals(botanica)) {
            throw new IntegrazioneRifiutata(Motivo.ALTRA_BOTANICA, "Il lotto è di " + lotto.botanica()
                    + ", il piano usa " + botanica);
        }
        if (!Origini.stessa(lotto.origine(), origineRichiesta)) {
            String origineLotto = lotto.origine() == null ? "" : Origini.pulisci(lotto.origine());
            throw new IntegrazioneRifiutata(Motivo.ORIGINE_DIVERSA,
                    "origine diversa: l'edizione deve restare fedele alla sua origine. Il piano chiede " + botanica
                            + " da «" + origineRichiesta + "», il lotto viene da «" + origineLotto + "»",
                    Map.of("origineRichiesta", origineRichiesta, "origineLotto", origineLotto));
        }
        for (IntegrazioneNelPiano gia : integrazioni) {
            if (gia.lottoId().equals(lotto.lottoId())) {
                throw new IntegrazioneRifiutata(Motivo.LOTTO_GIA_INTEGRATO, "Il lotto " + lotto.lottoId()
                        + " è già integrato nel piano (" + Chilogrammi.testo(gia.kg()) + " kg)");
            }
        }
    }

    private static BigDecimal kgDaIntegrare(BigDecimal kg) {
        try {
            return Chilogrammi.verifica(kg, "I kg da integrare");
        } catch (PianoNonValido nonValidi) {
            throw new IntegrazioneRifiutata(Motivo.KG_NON_VALIDI, nonValidi.getMessage());
        }
    }

    /**
     * min(previsti, max(0, previsti - Σ ordinati con l'origine richiesta) + Σ utilizzabili):
     * vedi la regola in cima. Un ordine di un'altra origine non copre i kg
     * previsti, quindi non entra in Σ ordinati: i kg dall'origine giusta si
     * aspettano ancora. I lotti integrati sono utilizzabili ma non sono
     * ordini: non entrano in Σ ordinati.
     */
    private BigDecimal ricalcolaDisponibili() {
        BigDecimal ordinati = Chilogrammi.ZERO;
        BigDecimal utilizzabili = Chilogrammi.ZERO;
        for (ConsegnaNelPiano applicata : consegne) {
            if (Origini.stessa(applicata.origine(), origineRichiesta)) {
                ordinati = ordinati.add(applicata.kgOrdinati());
            }
            utilizzabili = utilizzabili.add(applicata.kgUtilizzabili());
        }
        for (IntegrazioneNelPiano integrata : integrazioni) {
            utilizzabili = utilizzabili.add(integrata.kg());
        }
        BigDecimal ancoraAttesi = kgPrevisti.subtract(ordinati).max(Chilogrammi.ZERO);
        return ancoraAttesi.add(utilizzabili).min(kgPrevisti);
    }

    private static void verifica(ConsegnaArrivata consegna) {
        if (consegna == null || consegna.ordineId() == null || consegna.lottoId() == null) {
            throw new PianoNonValido("La consegna deve indicare l'ordine e il lotto");
        }
        if (consegna.botanica() == null || consegna.botanica().isBlank()) {
            throw new PianoNonValido("La consegna deve indicare la botanica");
        }
        if (consegna.origine() == null || consegna.origine().isBlank()) {
            throw new PianoNonValido("La consegna deve indicare l'origine del lotto");
        }
        if (consegna.origine().length() > MASSIMO_ORIGINE * 2) {
            throw new PianoNonValido("L'origine del lotto è troppo lunga");
        }
        Chilogrammi.verifica(consegna.kgOrdinati(), "I kg ordinati");
        if (consegna.kgArrivati() == null || consegna.kgArrivati().signum() < 0
                || consegna.kgArrivati().stripTrailingZeros().scale() > 3) {
            throw new PianoNonValido("I kg arrivati devono essere zero o più, al grammo");
        }
        if (consegna.kgArrivati().compareTo(consegna.kgOrdinati()) > 0) {
            throw new PianoNonValido("Sono arrivati più kg di quelli ordinati");
        }
        if (consegna.esitoQualita() == null || consegna.consegnataIl() == null) {
            throw new PianoNonValido("La consegna deve indicare l'esito qualità e la data");
        }
    }

    /** Il motivo in italiano: va nel piano, nell'evento e nei log. */
    private static String motivo(ConsegnaArrivata consegna, BigDecimal ordinati, BigDecimal arrivati, String origine,
                                 boolean stessaOrigine) {
        String suKg = Chilogrammi.testo(arrivati) + " su " + Chilogrammi.testo(ordinati) + " kg";
        if (!stessaOrigine) {
            return "origine diversa (" + origine + "): " + suKg + " arrivati, nessuno utilizzabile per l'edizione";
        }
        if (!consegna.conforme()) {
            return "lotto non conforme al controllo qualità: " + suKg + " arrivati, nessuno utilizzabile";
        }
        if (arrivati.compareTo(ordinati) < 0) {
            return "consegna ridotta: " + suKg;
        }
        return "consegna completa: " + suKg;
    }

    // ---------------------------------------------------------------- eventi di dominio

    /** Pubblicati da repository.save(piano): li raccoglie ScritturaOutbox. */
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

    public UUID edizioneId() {
        return edizioneId;
    }

    public UUID formulazioneId() {
        return formulazioneId;
    }

    public int formulazioneVersione() {
        return formulazioneVersione;
    }

    public String botanica() {
        return botanica;
    }

    public BigDecimal grammiPerBottiglia() {
        return grammiPerBottiglia;
    }

    public String origineRichiesta() {
        return origineRichiesta;
    }

    public BigDecimal kgPrevisti() {
        return kgPrevisti;
    }

    public BigDecimal kgDisponibili() {
        return kgDisponibili;
    }

    public int tiraturaPrevista() {
        return tiraturaPrevista;
    }

    /**
     * Quante bottiglie si possono riempire con i kg disponibili, per difetto:
     * 10,8 kg a 20 g per bottiglia = 540. Lo stesso conto di Edizioni.
     */
    public int bottiglieProducibili() {
        return kgDisponibili.multiply(BigDecimal.valueOf(1000))
                .divide(grammiPerBottiglia, 0, RoundingMode.FLOOR)
                .intValueExact();
    }

    public List<ConsegnaNelPiano> consegne() {
        return List.copyOf(consegne);
    }

    public List<IntegrazioneNelPiano> integrazioni() {
        return List.copyOf(integrazioni);
    }

    public Optional<IntegrazioneNelPiano> integrazioneDel(UUID lottoId) {
        return integrazioni.stream().filter(integrata -> integrata.lottoId().equals(lottoId)).findFirst();
    }

    public Instant creatoIl() {
        return creatoIl;
    }

    public Instant aggiornatoIl() {
        return aggiornatoIl;
    }
}
