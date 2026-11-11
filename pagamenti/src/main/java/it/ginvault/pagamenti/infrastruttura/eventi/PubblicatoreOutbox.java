package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seconda metà della TRANSACTIONAL OUTBOX: ogni secondo prende gli eventi non
 * ancora pubblicati, li manda su Kafka uno alla volta e segna quelli confermati.
 *
 * - Kafka giù: il primo invio fallisce dopo pochi secondi (max.block.ms,
 *   delivery.timeout.ms in application.yml); la riga resta da pubblicare, con
 *   il numero di tentativi e l'errore, e il giro si ferma lì. Al giro dopo si
 *   riprova dallo stesso evento: l'ordine degli eventi non cambia.
 * - Kafka torna: gli eventi in attesa partono, nell'ordine in cui sono nati.
 * - Garanzia "almeno una volta": se Kafka scrive il messaggio ma la conferma
 *   si perde, al giro dopo lo stesso evento (stesso eventId) parte di nuovo.
 *   Per questo i consumer scartano gli eventId già visti (Edizioni, passo 3
 *   della storia di Giulia).
 *
 * Il giro è in una transazione con un blocco advisory di PostgreSQL: con più
 * repliche di pagamenti ne lavora una sola alla volta.
 */
@Component
public class PubblicatoreOutbox {

    private static final Logger LOG = LoggerFactory.getLogger(PubblicatoreOutbox.class);

    /** Il numero del blocco advisory: uno qualsiasi, purché sempre lo stesso ("outbox" in esadecimale). */
    private static final long BLOCCO = 0x6f7574626f78L;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock orologio;
    private final int lotto;
    private final Duration attesaConferma;

    public PubblicatoreOutbox(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock orologio,
                              @Value("${pagamenti.outbox.lotto:50}") int lotto,
                              @Value("${pagamenti.outbox.attesa-conferma:5s}") Duration attesaConferma) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.orologio = orologio;
        this.lotto = lotto;
        this.attesaConferma = attesaConferma;
    }

    @Scheduled(fixedDelayString = "${pagamenti.outbox.intervallo-ms:1000}")
    @Transactional
    public void pubblica() {
        if (!outbox.bloccaPublisher(BLOCCO)) {
            return;   // un'altra replica sta già pubblicando
        }
        List<RigaOutbox> righe = outbox.daPubblicare(lotto);
        for (RigaOutbox riga : righe) {
            try {
                kafka.send(riga.topic(), riga.chiave(), riga.messaggio())
                        .get(attesaConferma.toMillis(), TimeUnit.MILLISECONDS);
                if (riga.tentativi() > 0) {
                    LOG.info("Evento {} {} pubblicato al tentativo {}", riga.tipo(), riga.eventId(),
                            riga.tentativi() + 1);
                }
                riga.pubblicata(orologio.instant());
            } catch (InterruptedException interrotto) {
                Thread.currentThread().interrupt();
                riga.fallita(interrotto);
                return;
            } catch (Exception errore) {
                riga.fallita(errore);
                // Un avviso al primo errore e poi ogni 30 tentativi: con Kafka giù
                // il log non si riempie di righe uguali
                if (riga.tentativi() == 1 || riga.tentativi() % 30 == 0) {
                    LOG.warn("Kafka non ha confermato l'evento {} {} (tentativo {}): resta nell'outbox e"
                            + " riparte al prossimo giro. Causa: {}", riga.tipo(), riga.eventId(),
                            riga.tentativi(), riga.ultimoErrore());
                }
                return;   // gli eventi dopo aspettano: l'ordine non cambia
            }
        }
    }
}
