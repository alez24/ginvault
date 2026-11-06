package it.ginvault.edizioni.infrastruttura.eventi;

import it.ginvault.edizioni.dominio.TiraturaRidotta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Riceve l'evento di dominio TiraturaRidotta, pubblicato DENTRO il processo
 * da Spring Data quando il servizio salva l'Edizione.
 *
 * @TransactionalEventListener (fase AFTER_COMMIT): il metodo parte solo dopo
 * il commit. Se il salvataggio fallisce (es. modifica concorrente -> 409),
 * l'evento non esce: non si annuncia una riduzione che non è avvenuta.
 *
 * Oggi si limita a scriverlo nel log. Con la storia di Elena questa classe
 * diventerà il produttore Kafka: l'evento andrà su un topic letto da
 * Notifiche, che avviserà chi ha prenotato. (Per non perdere eventi se Kafka è
 * giù proprio dopo il commit, la soluzione classica è la "transactional
 * outbox": l'evento si salva in una tabella nella stessa transazione e un
 * processo separato lo pubblica.)
 */
@Component
public class InoltroTiratureRidotte {

    private static final Logger LOG = LoggerFactory.getLogger(InoltroTiratureRidotte.class);

    @TransactionalEventListener
    public void tiraturaRidotta(TiraturaRidotta evento) {
        LOG.info("Evento TiraturaRidotta: edizione {} da {} a {} bottiglie, nuove quote {}",
                evento.edizioneId(), evento.tiraturaVecchia(), evento.tiraturaNuova(), evento.quote());
    }
}
