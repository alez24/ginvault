package it.ginvault.edizioni.infrastruttura.scadenze;

import java.util.List;
import java.util.UUID;

import it.ginvault.edizioni.applicazione.ServizioPrenotazioni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Il timeout della SAGA: ogni 30 secondi (edizioni.vendita.controllo-scadenze-ms)
 * annulla le prenotazioni ancora in attesa di pagamento con il tempo finito,
 * e le loro bottiglie tornano libere.
 *
 * Ogni prenotazione si annulla nella sua transazione, con l'edizione bloccata:
 *  - con più repliche di Edizioni lo stesso giro può partire due volte; la
 *    seconda trova la prenotazione già annullata e non fa niente;
 *  - un pagamento riuscito che arriva insieme alla scadenza aspetta il
 *    blocco: o conferma prima, e la scadenza non tocca più la prenotazione, o
 *    arriva dopo, e chiede il rimborso.
 * Una prenotazione che non si riesce ad annullare (database occupato) resta
 * per il giro dopo.
 */
@Component
class ScadenzaPrenotazioni {

    private static final Logger log = LoggerFactory.getLogger(ScadenzaPrenotazioni.class);

    private final ServizioPrenotazioni servizio;
    private final int lotto;

    ScadenzaPrenotazioni(ServizioPrenotazioni servizio, @Value("${edizioni.vendita.scadenze-per-giro:100}") int lotto) {
        this.servizio = servizio;
        this.lotto = lotto;
    }

    @Scheduled(fixedDelayString = "${edizioni.vendita.controllo-scadenze-ms:30000}",
            initialDelayString = "${edizioni.vendita.controllo-scadenze-ms:30000}")
    void annullaLeScadute() {
        List<UUID> scadute;
        try {
            scadute = servizio.scadute(lotto);
        } catch (RuntimeException errore) {
            log.warn("Controllo delle prenotazioni scadute non riuscito, riprovo al prossimo giro: {}",
                    errore.getMessage());
            return;
        }
        for (UUID id : scadute) {
            try {
                if (servizio.scadi(id)) {
                    log.info("Prenotazione {} scaduta: annullata, bottiglie tornate libere", id);
                }
            } catch (RuntimeException errore) {
                log.warn("Prenotazione {} scaduta ma non annullata, riprovo al prossimo giro: {}", id,
                        errore.getMessage());
            }
        }
    }
}
