package it.ginvault.produzione.applicazione;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.applicazione.Viste.PianoVista;
import it.ginvault.produzione.dominio.ConsegnaArrivata;
import it.ginvault.produzione.dominio.EsitoConsegna;
import it.ginvault.produzione.dominio.PianoDiProduzione;
import it.ginvault.produzione.dominio.PianoNonValido;
import it.ginvault.produzione.dominio.PianoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casi d'uso della produzione. I primi due li avviano gli eventi Kafka
 * (ElaborazioneEventi), che apre la transazione: qui le transazioni si
 * aggiungono a quella, così piano, riga di outbox ed eventId elaborato
 * finiscono nello stesso commit.
 */
@Service
public class ServizioProduzione {

    private final PianoRepository piani;
    private final Clock orologio;

    public ServizioProduzione(PianoRepository piani, Clock orologio) {
        this.piani = piani;
        this.orologio = orologio;
    }

    /**
     * Il piano di un'edizione appena creata.
     *
     * @return false se il piano c'era già (lo stesso EdizioneCreata arrivato due volte)
     */
    @Transactional
    public boolean creaPiano(NuovoPiano dati) {
        if (piani.existsByEdizioneId(dati.edizioneId())) {
            return false;
        }
        piani.save(PianoDiProduzione.nuovo(dati.edizioneId(), dati.formulazioneId(), dati.formulazioneVersione(),
                dati.botanica(), dati.grammiPerBottiglia(), dati.origineRichiesta(), dati.kgPrevisti(),
                dati.tiraturaPrevista(), orologio.instant()));
        return true;
    }

    /**
     * Una consegna per un'edizione: il piano ricalcola i kg disponibili e, se
     * cambiano, registra ResaRicalcolata (che save passa a ScritturaOutbox).
     *
     * @throws PianoNonTrovato nessun piano per l'edizione (il consumer riprova)
     */
    @Transactional
    public EsitoConsegna registraConsegna(UUID edizioneId, ConsegnaArrivata consegna) {
        if (edizioneId == null) {
            throw new PianoNonValido("La consegna non indica l'edizione");
        }
        PianoDiProduzione piano = piani.findByEdizioneId(edizioneId)
                .orElseThrow(() -> new PianoNonTrovato("Nessun piano di produzione per l'edizione " + edizioneId));
        EsitoConsegna esito = piano.registraConsegna(consegna, orologio.instant());
        piani.save(piano);
        return esito;
    }

    /** Tutti i piani, dal più recente; con edizioneId solo quello dell'edizione (0 o 1). */
    @Transactional(readOnly = true)
    public List<PianoVista> piani(UUID edizioneId) {
        if (edizioneId != null) {
            return piani.findByEdizioneId(edizioneId).map(PianoVista::di).stream().toList();
        }
        return piani.findAllByOrderByCreatoIlDesc().stream().map(PianoVista::di).toList();
    }

    @Transactional(readOnly = true)
    public PianoVista piano(UUID id) {
        return piani.findById(id).map(PianoVista::di)
                .orElseThrow(() -> new PianoNonTrovato("Nessun piano di produzione con id " + id));
    }
}
