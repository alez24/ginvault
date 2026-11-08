package it.ginvault.magazzino.applicazione;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import it.ginvault.magazzino.applicazione.Viste.EsitoConsegna;
import it.ginvault.magazzino.applicazione.Viste.FornitoreVista;
import it.ginvault.magazzino.applicazione.Viste.LottoVista;
import it.ginvault.magazzino.applicazione.Viste.OrdineVista;
import it.ginvault.magazzino.applicazione.Viste.RiferimentoFornitore;
import it.ginvault.magazzino.dominio.Botaniche;
import it.ginvault.magazzino.dominio.DatiConsegna;
import it.ginvault.magazzino.dominio.Fornitore;
import it.ginvault.magazzino.dominio.FornitoreRepository;
import it.ginvault.magazzino.dominio.LottoDiBotanica;
import it.ginvault.magazzino.dominio.LottoRepository;
import it.ginvault.magazzino.dominio.OrdineDiAcquisto;
import it.ginvault.magazzino.dominio.OrdineNonValido;
import it.ginvault.magazzino.dominio.OrdineRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servizio applicativo del subdomain Approvvigionamento: un metodo per caso d'uso.
 */
@Service
public class ServizioApprovvigionamento {

    private final OrdineRepository ordini;
    private final LottoRepository lotti;
    private final FornitoreRepository fornitori;
    private final Clock orologio;

    public ServizioApprovvigionamento(OrdineRepository ordini, LottoRepository lotti, FornitoreRepository fornitori,
                                      Clock orologio) {
        this.ordini = ordini;
        this.lotti = lotti;
        this.fornitori = fornitori;
        this.orologio = orologio;
    }

    /** Elena ordina una botanica a un fornitore, per un'edizione. */
    @Transactional
    public OrdineVista creaOrdine(UUID fornitoreId, String botanica, BigDecimal kgOrdinati, LocalDate consegnaAttesa,
                                  UUID edizioneId) {
        Fornitore fornitore = fornitori.findById(fornitoreId)
                .orElseThrow(() -> new OrdineNonValido("Il fornitore " + fornitoreId + " non esiste"));
        OrdineDiAcquisto ordine = OrdineDiAcquisto.nuovo(fornitore.id(), botanica, kgOrdinati, consegnaAttesa,
                edizioneId, orologio.instant());
        ordini.save(ordine);
        return OrdineVista.di(ordine, riferimento(fornitore));
    }

    /**
     * La botanica è arrivata, anche ridotta. In UNA transazione:
     *  1. l'ordine registra la consegna e si chiude, e crea il lotto  (409 / 422)
     *  2. il lotto entra in magazzino
     *  3. la consegna entra nello storico del fornitore
     *  4. save(ordine): Spring Data pubblica l'evento ConsegnaRegistrata e
     *     ScritturaOutbox lo scrive nella tabella outbox, prima del commit
     *
     * O tutto o niente: se il commit fallisce non c'è né il lotto né l'evento;
     * se va a buon fine, l'evento è nel database e prima o poi arriva su Kafka
     * (PubblicatoreOutbox), anche se Kafka in questo momento è spento.
     */
    @Transactional
    public EsitoConsegna registraConsegna(UUID ordineId, DatiConsegna dati) {
        OrdineDiAcquisto ordine = ordini.findById(ordineId).orElseThrow(() -> new OrdineNonTrovato(ordineId));
        Fornitore fornitore = fornitori.findById(ordine.fornitoreId())
                .orElseThrow(() -> new IllegalStateException("Fornitore " + ordine.fornitoreId() + " mancante"));

        LottoDiBotanica lotto = ordine.registraConsegna(dati, fornitore.origine(), oggi(), orologio.instant());
        lotti.save(lotto);
        fornitore.annotaConsegna(ordine);
        fornitori.save(fornitore);
        ordini.save(ordine);

        RiferimentoFornitore riferimento = riferimento(fornitore);
        return new EsitoConsegna(OrdineVista.di(ordine, riferimento), LottoVista.di(lotto, riferimento, oggi()));
    }

    @Transactional(readOnly = true)
    public OrdineVista ordine(UUID id) {
        OrdineDiAcquisto ordine = ordini.findById(id).orElseThrow(() -> new OrdineNonTrovato(id));
        return OrdineVista.di(ordine, riferimenti(Set.of(ordine.fornitoreId())).get(ordine.fornitoreId()));
    }

    /** Tutti gli ordini, dal più recente. */
    @Transactional(readOnly = true)
    public List<OrdineVista> ordini() {
        List<OrdineDiAcquisto> elenco = ordini.findAllByOrderByCreatoIlDesc();
        Map<UUID, RiferimentoFornitore> nomi = riferimenti(elenco.stream().map(OrdineDiAcquisto::fornitoreId).toList());
        return elenco.stream().map(ordine -> OrdineVista.di(ordine, nomi.get(ordine.fornitoreId()))).toList();
    }

    @Transactional(readOnly = true)
    public LottoVista lotto(UUID id) {
        LottoDiBotanica lotto = lotti.findById(id).orElseThrow(() -> new LottoNonTrovato(id));
        return LottoVista.di(lotto, riferimenti(Set.of(lotto.fornitoreId())).get(lotto.fornitoreId()), oggi());
    }

    /**
     * I lotti in magazzino, dal più recente.
     *
     * @param botanica facoltativa: solo i lotti di quella botanica ("Lavanda" = "lavanda")
     */
    @Transactional(readOnly = true)
    public List<LottoVista> lotti(String botanica) {
        List<LottoDiBotanica> elenco = botanica == null || botanica.isBlank()
                ? lotti.findAllByOrderByRicevutoIlDesc()
                : lotti.findByBotanicaOrderByRicevutoIlDesc(Botaniche.normalizza(botanica));
        Map<UUID, RiferimentoFornitore> nomi = riferimenti(elenco.stream().map(LottoDiBotanica::fornitoreId).toList());
        LocalDate oggi = oggi();
        return elenco.stream().map(lotto -> LottoVista.di(lotto, nomi.get(lotto.fornitoreId()), oggi)).toList();
    }

    @Transactional(readOnly = true)
    public FornitoreVista fornitore(UUID id) {
        return fornitori.findById(id).map(FornitoreVista::di).orElseThrow(() -> new FornitoreNonTrovato(id));
    }

    @Transactional(readOnly = true)
    public List<FornitoreVista> fornitori() {
        return fornitori.findAllByOrderByNomeAsc().stream().map(FornitoreVista::di).toList();
    }

    private LocalDate oggi() {
        return LocalDate.now(orologio);
    }

    private static RiferimentoFornitore riferimento(Fornitore fornitore) {
        return new RiferimentoFornitore(fornitore.id(), fornitore.nome());
    }

    /** Id e nome dei fornitori citati, con una sola query. */
    private Map<UUID, RiferimentoFornitore> riferimenti(Collection<UUID> ids) {
        return fornitori.findAllById(Set.copyOf(ids)).stream()
                .map(ServizioApprovvigionamento::riferimento)
                .collect(Collectors.toMap(RiferimentoFornitore::id, Function.identity()));
    }
}
