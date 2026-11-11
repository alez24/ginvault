package it.ginvault.pagamenti.applicazione;

import java.util.UUID;

/**
 * Un rimborso per una prenotazione che non ha pagamenti. Non dovrebbe
 * succedere: Edizioni chiede il rimborso solo dopo aver letto il
 * PagamentoRiuscito. Si riprova e, finiti i tentativi, il comando va nel dead
 * letter topic.
 */
public class PagamentoNonTrovato extends RuntimeException {

    public PagamentoNonTrovato(UUID prenotazioneId) {
        super("Nessun pagamento per la prenotazione " + prenotazioneId);
    }
}
