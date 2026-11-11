package it.ginvault.pagamenti.applicazione;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import it.ginvault.pagamenti.dominio.EsitoAddebito;
import it.ginvault.pagamenti.dominio.Pagamento;
import it.ginvault.pagamenti.dominio.PagamentoRepository;
import it.ginvault.pagamenti.dominio.ProviderDiPagamento;
import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;
import it.ginvault.pagamenti.dominio.StatoPagamento;

import org.springframework.stereotype.Service;

/**
 * I casi d'uso di Pagamenti: addebitare una prenotazione e restituire un
 * addebito. Li chiama ElaborazioneComandi, per i comandi che arrivano da
 * Edizioni su pagamenti.comandi.
 *
 * Ogni caso d'uso ha due metà, perché la chiamata a Stripe NON sta dentro una
 * transazione del database (terrebbe una connessione occupata per secondi, e
 * un rollback non annullerebbe un addebito già fatto):
 *
 *  1. esitoAddebito / eseguiRimborso: chiamano Stripe, solo se serve;
 *  2. registraAddebito / registraRimborso: salvano l'esito e i suoi eventi,
 *     nella transazione che segna anche il comando come elaborato.
 *
 * Se il database cade fra le due metà, il comando si rielabora e Stripe si
 * richiama con la STESSA chiave di idempotenza: restituisce l'esito di prima,
 * senza addebitare (o rimborsare) una seconda volta.
 */
@Service
public class ServizioPagamenti {

    private final PagamentoRepository pagamenti;
    private final ProviderDiPagamento provider;
    private final Clock orologio;

    public ServizioPagamenti(PagamentoRepository pagamenti, ProviderDiPagamento provider, Clock orologio) {
        this.pagamenti = pagamenti;
        this.provider = provider;
        this.orologio = orologio;
    }

    /** L'esito dell'addebito: quello già salvato, se la prenotazione ha già un pagamento, se no da Stripe. */
    public EsitoAddebito esitoAddebito(RichiestaDiPagamento richiesta) {
        return pagamenti.findByPrenotazioneId(richiesta.prenotazioneId())
                .map(Pagamento::esito)
                .orElseGet(() -> provider.addebita(richiesta));
    }

    /**
     * Salva il pagamento con il suo esito; se la prenotazione ne ha già uno,
     * racconta di nuovo quello (il primo esito vale).
     *
     * @return true per un pagamento nuovo, false se c'era già
     */
    public boolean registraAddebito(RichiestaDiPagamento richiesta, EsitoAddebito esito) {
        Optional<Pagamento> esistente = pagamenti.findByPrenotazioneId(richiesta.prenotazioneId());
        if (esistente.isPresent()) {
            Pagamento pagamento = esistente.get();
            pagamento.ripetiEsito(orologio.instant());
            pagamenti.save(pagamento);
            return false;
        }
        pagamenti.save(Pagamento.registra(richiesta, esito, orologio.instant()));
        return true;
    }

    /**
     * Restituisce l'addebito presso Stripe, se non è già stato restituito.
     *
     * @return l'id del rimborso (re_...)
     */
    public String eseguiRimborso(UUID prenotazioneId) {
        Pagamento pagamento = trova(prenotazioneId);
        if (pagamento.stato() == StatoPagamento.RIMBORSATO) {
            return pagamento.rimborsoStripe();
        }
        pagamento.controllaRimborsabile();
        return provider.rimborsa(prenotazioneId, pagamento.riferimentoStripe());
    }

    /** Segna il pagamento come rimborsato e racconta il rimborso. */
    public Pagamento registraRimborso(UUID prenotazioneId, String rimborso, String motivo) {
        Pagamento pagamento = trova(prenotazioneId);
        pagamento.rimborsato(rimborso, motivo, orologio.instant());
        return pagamenti.save(pagamento);
    }

    public Optional<Pagamento> perPrenotazione(UUID prenotazioneId) {
        return pagamenti.findByPrenotazioneId(prenotazioneId);
    }

    private Pagamento trova(UUID prenotazioneId) {
        return pagamenti.findByPrenotazioneId(prenotazioneId)
                .orElseThrow(() -> new PagamentoNonTrovato(prenotazioneId));
    }
}
