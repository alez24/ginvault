package it.ginvault.edizioni.applicazione;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import it.ginvault.edizioni.dominio.Acquirente;
import it.ginvault.edizioni.dominio.Club;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.EsitoPrenotazione;
import it.ginvault.edizioni.dominio.Prenotazione;
import it.ginvault.edizioni.dominio.PrenotazioneRepository;
import it.ginvault.edizioni.dominio.RegoleDiVendita;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servizio applicativo delle prenotazioni: un metodo per caso d'uso.
 * Le regole (prelazione, limite per persona, bottiglie libere, numeri di
 * bottiglia) sono negli aggregati Edizione e Prenotazione; qui si caricano i
 * dati e si salva.
 *
 * Regola dei blocchi: ogni passo che cambia una prenotazione (prenota, paga,
 * gli esiti di Pagamenti, la scadenza) blocca PRIMA la riga dell'edizione
 * (findPerModifica) e solo dopo legge la prenotazione. Così due passi sulla
 * stessa edizione non si sovrappongono mai: la scadenza e un pagamento
 * riuscito non vedono la stessa prenotazione "in attesa", e due conferme
 * nello stesso istante non prendono gli stessi numeri di bottiglia.
 */
@Service
public class ServizioPrenotazioni {

    private static final DateTimeFormatter GIORNO = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("Europe/Rome"));

    private final EdizioneRepository edizioni;
    private final PrenotazioneRepository prenotazioni;
    private final Club club;
    private final RegoleDiVendita regole;
    private final MetodiDiPagamento metodi;
    private final Clock orologio;

    public ServizioPrenotazioni(EdizioneRepository edizioni, PrenotazioneRepository prenotazioni, Club club,
                                RegoleDiVendita regole, MetodiDiPagamento metodi, Clock orologio) {
        this.edizioni = edizioni;
        this.prenotazioni = prenotazioni;
        this.club = club;
        this.regole = regole;
        this.metodi = metodi;
        this.orologio = orologio;
    }

    /**
     * Una persona prenota bottiglie di un'edizione, in un'unica transazione:
     *  1. l'edizione si legge con il blocco della riga: altre prenotazioni
     *     della stessa edizione aspettano la fine di questa;
     *  2. le prenotazioni che la persona ha già per l'edizione (limite per persona);
     *  3. Edizione.prenota controlla le regole, toglie le bottiglie dalla quota
     *     e crea la prenotazione, in attesa di pagamento;
     *  4. prenotazione ed edizione si salvano nello stesso commit.
     */
    @Transactional
    public PrenotazioneVista prenota(UUID edizioneId, Acquirente acquirente, int bottiglie) {
        Instant adesso = orologio.instant();
        Edizione edizione = edizioni.findPerModifica(edizioneId)
                .orElseThrow(() -> new EdizioneNonTrovata(edizioneId));
        List<Prenotazione> sueAltre = prenotazioni.findByEdizioneIdAndAcquirente(edizioneId, acquirente.id());
        Prenotazione prenotazione = edizione.prenota(acquirente, bottiglie, sueAltre, regole, adesso);
        prenotazioni.save(prenotazione);
        edizioni.save(edizione);
        return PrenotazioneVista.di(prenotazione, edizione, club.ritiroPer(edizione));
    }

    /**
     * Chi ha prenotato sceglie la carta di prova e chiede di pagare. Il
     * pagamento non parte da qui: nello stesso commit si scrive nell'outbox il
     * comando RichiestaPagamento, e l'esito arriva da Pagamenti su
     * pagamenti.esiti (pagamentoRiuscito o pagamentoFallito). La risposta è la
     * prenotazione ancora in attesa, con il pagamento richiesto.
     *
     * @throws MetodoNonAccettato     la carta non è nel menu (-> 400)
     * @throws PrenotazioneNonTrovata non c'è, o è di un altro (-> 404)
     * @throws it.ginvault.edizioni.dominio.PagamentoNonPossibile già chiesto, tempo finito, chiusa (-> 409)
     */
    @Transactional
    public PrenotazioneVista paga(UUID id, String acquirente, String metodo) {
        if (!metodi.accetta(metodo)) {
            throw new MetodoNonAccettato(metodo, metodi.accettati());
        }
        UUID edizioneId = prenotazioni.edizioneDi(id, acquirente).orElseThrow(() -> new PrenotazioneNonTrovata(id));
        Edizione edizione = edizioni.findPerModifica(edizioneId).orElseThrow(() -> new EdizioneNonTrovata(edizioneId));
        Prenotazione prenotazione = prenotazioni.findByIdAndAcquirente(id, acquirente)
                .orElseThrow(() -> new PrenotazioneNonTrovata(id));
        prenotazione.richiediPagamento(metodo, descrizione(prenotazione, edizione), orologio.instant());
        prenotazioni.save(prenotazione);
        return PrenotazioneVista.di(prenotazione, edizione, club.ritiroPer(edizione));
    }

    // ---------------------------------------------------------------- gli esiti di Pagamenti

    /**
     * PagamentoRiuscito: conferma con i numeri di bottiglia, oppure chiede il
     * rimborso se la prenotazione era già annullata. Si unisce alla
     * transazione del consumer (con evento_elaborato).
     *
     * @throws PrenotazioneNonTrovata un esito per una prenotazione che non c'è (-> edizioni.dlt)
     */
    @Transactional
    public EsitoPrenotazione pagamentoRiuscito(UUID prenotazioneId, UUID pagamentoId, String riferimento) {
        return conEdizioneBloccata(prenotazioneId, (edizione, prenotazione) ->
                edizione.pagamentoRiuscito(prenotazione, pagamentoId, riferimento, orologio.instant()));
    }

    /** PagamentoFallito: annulla e libera le bottiglie (compensazione). */
    @Transactional
    public EsitoPrenotazione pagamentoFallito(UUID prenotazioneId, String spiegazione) {
        return conEdizioneBloccata(prenotazioneId, (edizione, prenotazione) ->
                edizione.pagamentoFallito(prenotazione, spiegazione, orologio.instant()));
    }

    /** RimborsoEseguito: il pagamento arrivato tardi è stato restituito. */
    @Transactional
    public EsitoPrenotazione rimborsoEseguito(UUID prenotazioneId, String riferimento) {
        return conEdizioneBloccata(prenotazioneId, (edizione, prenotazione) ->
                prenotazione.rimborsata(riferimento, orologio.instant()));
    }

    // ---------------------------------------------------------------- la scadenza

    /** Fino a "quante" prenotazioni in attesa con il tempo finito, dalla più vecchia. */
    @Transactional(readOnly = true)
    public List<UUID> scadute(int quante) {
        return prenotazioni.scaduteAl(orologio.instant(), PageRequest.of(0, quante));
    }

    /**
     * Annulla una prenotazione scaduta e libera le sue bottiglie, nella sua
     * transazione. Se nel frattempo è stata pagata, confermata o annullata,
     * non cambia niente.
     *
     * @return true se è stata annullata adesso
     */
    @Transactional
    public boolean scadi(UUID prenotazioneId) {
        return conEdizioneBloccata(prenotazioneId, (edizione, prenotazione) ->
                edizione.scadenza(prenotazione, orologio.instant()));
    }

    // ---------------------------------------------------------------- lettura

    /** Le prenotazioni di una persona, dalla più recente, con le loro edizioni lette in una sola query. */
    @Transactional(readOnly = true)
    public List<PrenotazioneVista> sue(String acquirente) {
        List<Prenotazione> sue = prenotazioni.findByAcquirenteOrderByPrenotataIlDesc(acquirente);
        Map<UUID, Edizione> perId = edizioni.findAllById(sue.stream().map(Prenotazione::edizioneId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Edizione::id, Function.identity()));
        return sue.stream()
                .map(prenotazione -> {
                    Edizione edizione = perId.get(prenotazione.edizioneId());
                    return PrenotazioneVista.di(prenotazione, edizione, club.ritiroPer(edizione));
                })
                .toList();
    }

    /** Una prenotazione di chi chiede; quella di un altro risponde come se non ci fosse. */
    @Transactional(readOnly = true)
    public PrenotazioneVista trova(UUID id, String acquirente) {
        Prenotazione prenotazione = prenotazioni.findByIdAndAcquirente(id, acquirente)
                .orElseThrow(() -> new PrenotazioneNonTrovata(id));
        Edizione edizione = edizioni.findById(prenotazione.edizioneId())
                .orElseThrow(() -> new EdizioneNonTrovata(prenotazione.edizioneId()));
        return PrenotazioneVista.di(prenotazione, edizione, club.ritiroPer(edizione));
    }

    public RegoleDiVenditaVista regole() {
        return RegoleDiVenditaVista.di(regole, metodi);
    }

    // ---------------------------------------------------------------- aiuti

    /**
     * La regola dei blocchi: l'id dell'edizione senza caricare la
     * prenotazione, il blocco dell'edizione, poi la prenotazione letta dopo il
     * blocco. Edizione e prenotazione si salvano insieme: gli eventi di
     * dominio finiscono nell'outbox nello stesso commit.
     */
    private <R> R conEdizioneBloccata(UUID prenotazioneId, BiFunction<Edizione, Prenotazione, R> passo) {
        UUID edizioneId = prenotazioni.edizioneDi(prenotazioneId)
                .orElseThrow(() -> new PrenotazioneNonTrovata(prenotazioneId));
        Edizione edizione = edizioni.findPerModifica(edizioneId).orElseThrow(() -> new EdizioneNonTrovata(edizioneId));
        Prenotazione prenotazione = prenotazioni.findById(prenotazioneId)
                .orElseThrow(() -> new PrenotazioneNonTrovata(prenotazioneId));
        R esito = passo.apply(edizione, prenotazione);
        prenotazioni.save(prenotazione);
        edizioni.save(edizione);
        return esito;
    }

    /** La riga nella dashboard di Stripe: "GinVault: 2 bottiglie, edizione lavanda del 15/06/2026". */
    private static String descrizione(Prenotazione prenotazione, Edizione edizione) {
        return "GinVault: " + (prenotazione.bottiglie() == 1 ? "1 bottiglia" : prenotazione.bottiglie() + " bottiglie")
                + ", edizione " + edizione.botanicaDiStagione() + " del " + GIORNO.format(edizione.creataIl());
    }
}
