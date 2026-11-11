package it.ginvault.edizioni.applicazione;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.CatalogoRicette;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.EsitoRicalcolo;
import it.ginvault.edizioni.dominio.FormulazioneGiaUsata;
import it.ginvault.edizioni.dominio.FormulazioneInCatalogo;
import it.ginvault.edizioni.dominio.FormulazioneNonUtilizzabile;
import it.ginvault.edizioni.dominio.RegoleDiVendita;
import it.ginvault.edizioni.dominio.ResaDiProduzione;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Servizio applicativo delle edizioni: un metodo per caso d'uso.
 */
@Service
public class ServizioEdizioni {

    private final CatalogoRicette catalogo;
    private final EdizioneRepository repository;
    private final TransactionTemplate transazione;
    private final RegoleDiVendita regole;
    private final Clock orologio;

    public ServizioEdizioni(CatalogoRicette catalogo, EdizioneRepository repository,
                            PlatformTransactionManager transazioni, RegoleDiVendita regole, Clock orologio) {
        this.catalogo = catalogo;
        this.repository = repository;
        this.transazione = new TransactionTemplate(transazioni);
        this.regole = regole;
        this.orologio = orologio;
    }

    /**
     * Crea un'edizione da una formulazione di Ricette. L'ordine è voluto:
     *
     *  1. GET della formulazione su Ricette: deve esistere ed essere SCELTA,
     *     con la versione indicata                         (altrimenti 422 / 409)
     *  2. l'Edizione si costruisce IN MEMORIA, con tutte le sue regole: se non
     *     tornano, ci si ferma qui e la formulazione resta libera     (422).
     *     Nasce in prelazione, per la durata decisa dal Club
     *  3. POST /utilizzo su Ricette: la formulazione diventa USATA da questa
     *     edizione                       (409 se un'altra edizione l'ha presa)
     *  4. solo se il passo 3 va a buon fine, l'edizione si salva, e con lei
     *     l'evento EdizioneCreata nella tabella outbox: una transazione breve,
     *     solo attorno al salvataggio
     *
     * Ricette spento o in timeout al passo 1 o 3 -> 503 e nessuna edizione.
     *
     * NON TRANSAZIONALE nel suo insieme. Le due chiamate a Ricette e il salvataggio qui non sono
     * un'unica transazione: Ricette può segnare la formulazione USATA e poi il
     * salvataggio di Edizioni fallire (database giù, vincolo unique). In quel
     * caso la formulazione resta "usata" da un'edizione che non esiste, e va
     * sistemata a mano. Il metodo volutamente NON è @Transactional: una
     * transazione del database non annullerebbe comunque la POST già fatta a
     * Ricette, e terrebbe occupata una connessione mentre si aspetta la rete.
     * La transazione c'è solo al passo 4 (TransactionTemplate): serve perché
     * edizione e riga di outbox entrino nello stesso commit.
     * Con Kafka questo diventerà una SAGA: Edizioni salva l'edizione e pubblica
     * l'evento EdizioneCreata; Ricette la segna USATA e, se non può, risponde con
     * un evento che fa annullare l'edizione (azione di compensazione).
     */
    public EdizioneVista crea(UUID formulazioneId, int versione, BigDecimal botanicaDisponibileKg,
                              String origineBotanica, Map<Canale, Integer> quote) {
        // 1. lettura da Ricette (attraverso l'Anti-Corruption Layer)
        FormulazioneInCatalogo formulazione = catalogo.trova(formulazioneId)
                .orElseThrow(() -> new FormulazioneNonUtilizzabile(
                        "La formulazione " + formulazioneId + " non esiste in Ricette"));

        // 2. regole di dominio, ancora tutto in memoria
        Edizione edizione = Edizione.nuova(formulazione, versione, botanicaDisponibileKg, origineBotanica, quote,
                regole.durataPrelazione());

        // 3. da qui la formulazione non si riusa più
        catalogo.segnaUsata(edizione.formulazione(), edizione.id());

        // 4. salvataggio, con il vincolo unique come rete di sicurezza. Nella
        //    transazione Spring Data pubblica EdizioneCreata e ScritturaOutbox lo
        //    scrive nell'outbox prima del commit
        try {
            transazione.executeWithoutResult(stato -> repository.saveAndFlush(edizione));
        } catch (DataIntegrityViolationException doppione) {
            throw new FormulazioneGiaUsata("Esiste già un'edizione con la formulazione " + formulazioneId
                    + " (versione " + versione + "): una formulazione usata non si riusa mai");
        }
        return vista(edizione);
    }

    /**
     * Nuovi kg di botanica: l'Edizione ricalcola tiratura e quote (regole
     * nell'aggregato). Non chiama Ricette: i grammi per bottiglia sono già qui.
     */
    @Transactional
    public EdizioneVista ricalcola(UUID id, BigDecimal botanicaDisponibileKg) {
        Edizione edizione = repository.findById(id).orElseThrow(() -> new EdizioneNonTrovata(id));
        edizione.ricalcola(botanicaDisponibileKg);
        // save() esplicito: per salvare basterebbe il dirty checking, ma è qui che
        // Spring Data pubblica gli eventi di dominio dell'aggregato (@DomainEvents)
        repository.save(edizione);
        return vista(edizione);
    }

    /**
     * La resa ricalcolata da Produzione (consumer di produzione.eventi):
     * l'Edizione la applica con le regole del ricalcolo, oppure la rifiuta e
     * registra RicalcoloRifiutato. Gira nella transazione del consumer:
     * edizione, riga di outbox ed evento_elaborato entrano nello stesso commit.
     *
     * @throws EdizioneNonTrovata l'edizione non c'è: riprovare non serve (dead letter)
     */
    @Transactional
    public EsitoRicalcolo applicaResa(UUID id, ResaDiProduzione resa) {
        Edizione edizione = repository.findById(id).orElseThrow(() -> new EdizioneNonTrovata(id));
        EsitoRicalcolo esito = edizione.applicaResa(resa);
        // Anche se l'edizione non cambia (rifiuto): save() pubblica RicalcoloRifiutato
        repository.save(edizione);
        return esito;
    }

    @Transactional(readOnly = true)
    public EdizioneVista trova(UUID id) {
        return repository.findById(id).map(this::vista).orElseThrow(() -> new EdizioneNonTrovata(id));
    }

    /** Elenco per il back-office, dalla più recente. Non chiama Ricette: legge solo il proprio database. */
    @Transactional(readOnly = true)
    public List<EdizioneVista> elenco() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "creataIl")).stream()
                .map(this::vista)
                .toList();
    }

    /**
     * Il distillatore apre la vendita prima che la prelazione finisca da sola:
     * da adesso soci e clienti prenotano dalla quota ONLINE. Su un'edizione già
     * in vendita non cambia niente (si può ripetere). L'edizione si legge con il
     * blocco, come per le prenotazioni: una prenotazione in corso finisce prima.
     */
    @Transactional
    public EdizioneVista apriVendita(UUID id) {
        Edizione edizione = repository.findPerModifica(id).orElseThrow(() -> new EdizioneNonTrovata(id));
        edizione.apriVendita(orologio.instant());
        repository.save(edizione);
        return vista(edizione);
    }

    private EdizioneVista vista(Edizione edizione) {
        return EdizioneVista.di(edizione, orologio.instant());
    }
}
