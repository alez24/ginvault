package it.ginvault.edizioni.applicazione;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import it.ginvault.edizioni.dominio.Acquirente;
import it.ginvault.edizioni.dominio.Club;
import it.ginvault.edizioni.dominio.Edizione;
import it.ginvault.edizioni.dominio.EdizioneRepository;
import it.ginvault.edizioni.dominio.Prenotazione;
import it.ginvault.edizioni.dominio.PrenotazioneRepository;
import it.ginvault.edizioni.dominio.RegoleDiVendita;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servizio applicativo delle prenotazioni: un metodo per caso d'uso.
 * Le regole (prelazione, limite per persona, bottiglie libere) sono
 * nell'aggregato Edizione; qui si caricano i dati e si salva.
 */
@Service
public class ServizioPrenotazioni {

    private final EdizioneRepository edizioni;
    private final PrenotazioneRepository prenotazioni;
    private final Club club;
    private final RegoleDiVendita regole;
    private final Clock orologio;

    public ServizioPrenotazioni(EdizioneRepository edizioni, PrenotazioneRepository prenotazioni, Club club,
                                RegoleDiVendita regole, Clock orologio) {
        this.edizioni = edizioni;
        this.prenotazioni = prenotazioni;
        this.club = club;
        this.regole = regole;
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
        return RegoleDiVenditaVista.di(regole);
    }
}
