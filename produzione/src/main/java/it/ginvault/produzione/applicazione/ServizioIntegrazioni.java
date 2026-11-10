package it.ginvault.produzione.applicazione;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.applicazione.Viste.EsitoIntegrazioneVista;
import it.ginvault.produzione.applicazione.Viste.LottiDelPianoVista;
import it.ginvault.produzione.applicazione.Viste.PianoVista;
import it.ginvault.produzione.dominio.EsitoIntegrazione;
import it.ginvault.produzione.dominio.IntegrazioneRifiutata;
import it.ginvault.produzione.dominio.IntegrazioneRifiutata.Motivo;
import it.ginvault.produzione.dominio.LottoProposto;
import it.ginvault.produzione.dominio.PianoDiProduzione;
import it.ginvault.produzione.dominio.PianoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Il caso d'uso della partnership con il Magazzino: Marco integra nel piano un
 * lotto proposto da Elena (POST /piani/{id}/integrazioni).
 *
 *   1. il piano (transazione breve, sola lettura); lo stesso lotto con gli
 *      stessi kg già integrato -> GIA_INTEGRATO, senza chiamare il magazzino
 *      (Marco che preme due volte non riceve un errore)
 *   2. Giacenze.Disponibilita: il lotto, con origine e kg liberi   (gRPC)
 *   3. la regola di dominio: origine, kg mancanti...               (409 se no)
 *   4. Giacenze.RiservaLotto: i kg diventano dell'edizione         (gRPC)
 *   5. il piano integra il lotto e ricalcola la resa: piano e riga di outbox
 *      di ResaRicalcolata nello stesso commit
 *
 * Le chiamate gRPC stanno FUORI dalle transazioni: con il magazzino lento una
 * connessione al database resterebbe occupata fino alla deadline.
 *
 * La regola viene prima della riserva: un lotto con un'altra origine non
 * viene mai riservato, quindi non c'è niente da annullare.
 *
 * Se il passo 5 fallisce dopo la riserva (database giù, il piano cambiato nel
 * frattempo...), COMPENSAZIONE: Giacenze.AnnullaRiserva libera i kg in
 * magazzino, best effort e con un log. Prima però si rilegge il piano: se lo
 * stesso lotto c'è già (Marco ha premuto due volte e l'altra richiesta ha
 * vinto) la riserva è usata e non si tocca. Se anche l'annullamento fallisce
 * resta una riserva orfana (log ERROR): ripetendo la richiesta, la riserva
 * idempotente restituisce quella che c'è e il piano si allinea.
 */
@Service
public class ServizioIntegrazioni {

    private static final Logger log = LoggerFactory.getLogger(ServizioIntegrazioni.class);

    private final PianoRepository piani;
    private final Giacenze giacenze;
    private final Clock orologio;
    private final TransactionTemplate scrittura;
    private final TransactionTemplate lettura;

    public ServizioIntegrazioni(PianoRepository piani, Giacenze giacenze, Clock orologio,
                                PlatformTransactionManager transazioni) {
        this.piani = piani;
        this.giacenze = giacenze;
        this.orologio = orologio;
        this.scrittura = new TransactionTemplate(transazioni);
        this.lettura = new TransactionTemplate(transazioni);
        this.lettura.setReadOnly(true);
    }

    /**
     * @throws PianoNonTrovato         il piano non esiste (404)
     * @throws IntegrazioneRifiutata   la regola del piano dice no (409, con il motivo)
     * @throws RiservaNonRiuscita      il magazzino dice no (409)
     * @throws MagazzinoNonDisponibile il magazzino non risponde in 2 secondi (503)
     */
    public EsitoIntegrazioneVista integra(UUID pianoId, UUID lottoId, BigDecimal kg) {
        EsitoIntegrazioneVista ripetuta = lettura.execute(stato -> {
            PianoDiProduzione piano = trova(pianoId);
            boolean stessiKg = piano.integrazioneDel(lottoId)
                    .filter(integrata -> kg != null && integrata.kg().compareTo(kg) == 0)
                    .isPresent();
            return stessiKg ? new EsitoIntegrazioneVista(EsitoIntegrazione.GIA_INTEGRATO, PianoVista.di(piano)) : null;
        });
        if (ripetuta != null) {
            return ripetuta;
        }
        String botanica = lettura.execute(stato -> trova(pianoId).botanica());

        LottoProposto lotto;
        BigDecimal daRiservare;
        try {
            lotto = giacenze.disponibilita(botanica).stream()
                    .filter(candidato -> candidato.lottoId().equals(lottoId))
                    .findFirst()
                    .orElseThrow(() -> new IntegrazioneRifiutata(Motivo.LOTTO_NON_DISPONIBILE, "Il lotto "
                            + lottoId + " non è tra i lotti di " + botanica + " utilizzabili in magazzino: non"
                            + " esiste, è di un'altra botanica, è scaduto o non ha passato il controllo qualità"));
            LottoProposto proposto = lotto;
            daRiservare = lettura.execute(stato -> trova(pianoId).verificaProposta(proposto, kg));
        } catch (IntegrazioneRifiutata rifiuto) {
            log.info("Integrazione del lotto {} nel piano {} rifiutata ({}): {}", lottoId, pianoId,
                    rifiuto.motivo(), rifiuto.getMessage());
            throw rifiuto;
        }

        Giacenze.Riserva riserva = giacenze.riservaLotto(lottoId, pianoId, daRiservare);

        LottoProposto riservato = lotto;
        try {
            return scrittura.execute(stato -> {
                PianoDiProduzione piano = trova(pianoId);
                BigDecimal prima = piano.kgDisponibili();
                EsitoIntegrazione esito = piano.integraLotto(riservato, riserva.kgRiservati(), orologio.instant());
                piani.save(piano);
                log.info("Lotto {} integrato nel piano {}: {} kg riservati, kg disponibili {} -> {} ({})", lottoId,
                        pianoId, riserva.kgRiservati(), prima, piano.kgDisponibili(), esito);
                return new EsitoIntegrazioneVista(esito, PianoVista.di(piano));
            });
        } catch (RuntimeException errore) {
            return pianoNonSalvato(pianoId, lottoId, riserva, errore);
        }
    }

    /**
     * Il piano non si è salvato dopo la riserva. Se il lotto è comunque nel
     * piano, l'ha integrato un'altra richiesta nello stesso momento: la
     * riserva serve e Marco riceve GIA_INTEGRATO. Altrimenti si annulla la
     * riserva e Marco riceve l'errore di partenza.
     *
     * Se il piano non si riesce nemmeno a rileggere (database giù), si annulla
     * lo stesso: quasi sempre il piano non è stato salvato. L'eccezione è un
     * commit riuscito di cui si è persa la risposta (limiti in docs/grpc.md).
     */
    private EsitoIntegrazioneVista pianoNonSalvato(UUID pianoId, UUID lottoId, Giacenze.Riserva riserva,
                                                  RuntimeException errore) {
        PianoVista conIlLotto = null;
        try {
            conIlLotto = lettura.execute(stato -> {
                PianoDiProduzione piano = trova(pianoId);
                return piano.integrazioneDel(lottoId).isPresent() ? PianoVista.di(piano) : null;
            });
        } catch (RuntimeException rilettura) {
            log.warn("Dopo il salvataggio fallito il piano {} non si rilegge ({}): si annulla la riserva", pianoId,
                    rilettura.toString());
        }
        if (conIlLotto != null) {
            log.info("Il salvataggio del piano {} è fallito ({}), ma il lotto {} è già nel piano: l'ha integrato "
                    + "un'altra richiesta, la riserva resta", pianoId, errore.toString(), lottoId);
            return new EsitoIntegrazioneVista(EsitoIntegrazione.GIA_INTEGRATO, conIlLotto);
        }
        compensa(pianoId, lottoId, riserva, errore);
        throw errore;
    }

    /** Giacenze.AnnullaRiserva, best effort: un errore qui si logga e non nasconde quello di partenza. */
    private void compensa(UUID pianoId, UUID lottoId, Giacenze.Riserva riserva, RuntimeException causa) {
        try {
            Giacenze.Annullamento annullamento = giacenze.annullaRiserva(lottoId, pianoId);
            log.warn("Compensazione: il piano {} non si è salvato ({}), riserva del lotto {} annullata in "
                            + "magazzino ({} kg liberati{})", pianoId, causa.toString(), lottoId,
                    annullamento.kgLiberati(), annullamento.nessunaRiserva() ? ", nessuna riserva trovata" : "");
        } catch (RuntimeException errore) {
            log.error("Compensazione NON riuscita: il piano {} non si è salvato ({}) e i {} kg riservati del lotto {}"
                            + " restano in magazzino (riserva orfana). Si sistema ripetendo l'integrazione: la riserva"
                            + " è idempotente", pianoId, causa.toString(), riserva.kgRiservati(), lottoId, errore);
            causa.addSuppressed(errore);
        }
    }

    /**
     * I lotti della botanica del piano in magazzino (Giacenze.Disponibilita),
     * con l'indicazione di quali hanno l'origine richiesta: la pagina del piano
     * li mostra a Marco prima che provi a integrarli.
     *
     * @throws PianoNonTrovato         il piano non esiste (404)
     * @throws MagazzinoNonDisponibile il magazzino non risponde in 2 secondi (503)
     */
    public LottiDelPianoVista lotti(UUID pianoId) {
        PianoVista piano = lettura.execute(stato -> PianoVista.di(trova(pianoId)));
        List<LottoProposto> lotti = giacenze.disponibilita(piano.botanica());
        return LottiDelPianoVista.di(piano, lotti);
    }

    private PianoDiProduzione trova(UUID pianoId) {
        return piani.findById(pianoId)
                .orElseThrow(() -> new PianoNonTrovato("Nessun piano di produzione con id " + pianoId));
    }
}
