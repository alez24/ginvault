package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Duration;

import it.ginvault.pagamenti.dominio.RimborsoNonPossibile;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Cosa succede quando un comando non si riesce a elaborare (come in Produzione).
 *
 *  - Errori che possono passare (Stripe che non risponde o risponde 5xx,
 *    database occupato): si riprova con attese crescenti, 1 s, 2 s, 4 s, 8 s
 *    (pagamenti.consumer.* in application.yml). Ogni tentativo richiama Stripe
 *    con la stessa chiave di idempotenza: nessun doppio addebito. Mentre si
 *    riprova, gli altri comandi della stessa partizione aspettano.
 *  - Errori che non passano (ComandoNonValido, RimborsoNonPossibile): nessun
 *    nuovo tentativo.
 *  - Finiti i tentativi, il comando va nel DEAD LETTER TOPIC pagamenti.dlt,
 *    nella stessa partizione, con gli header kafka_dlt-* (topic, partizione e
 *    offset d'origine, eccezione e messaggio d'errore). Non si perde: lo si
 *    può leggere, correggere e rimandare. L'offset avanza e i comandi dopo
 *    ripartono.
 *
 * Una carta rifiutata NON è un errore: diventa un PagamentoFallito.
 */
@Configuration
public class ConfigurazioneKafka {

    public static final String COMANDI = "pagamenti.comandi";
    public static final String DEAD_LETTER = "pagamenti.dlt";

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurazioneKafka.class);

    @Bean
    DefaultErrorHandler gestoreErroriKafka(KafkaTemplate<String, String> kafka,
                                           @Value("${pagamenti.consumer.tentativi:4}") int tentativi,
                                           @Value("${pagamenti.consumer.attesa-iniziale:1s}") Duration iniziale,
                                           @Value("${pagamenti.consumer.attesa-massima:8s}") Duration massima) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafka,
                (messaggio, errore) -> new TopicPartition(DEAD_LETTER, messaggio.partition()));
        ExponentialBackOffWithMaxRetries attese = new ExponentialBackOffWithMaxRetries(tentativi);
        attese.setInitialInterval(iniziale.toMillis());
        attese.setMultiplier(2);
        attese.setMaxInterval(massima.toMillis());

        DefaultErrorHandler gestore = new DefaultErrorHandler(deadLetter, attese);
        gestore.addNotRetryableExceptions(ComandoNonValido.class, RimborsoNonPossibile.class);
        gestore.setRetryListeners(new RetryListener() {

            @Override
            public void failedDelivery(ConsumerRecord<?, ?> messaggio, Exception errore, int tentativo) {
                LOG.warn("Comando {} non elaborato (tentativo {}): {}", dove(messaggio), tentativo, causa(errore));
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> messaggio, Exception errore) {
                LOG.error("Comando {} spostato in {}: {}", dove(messaggio), DEAD_LETTER, causa(errore));
            }
        });
        return gestore;
    }

    private static String dove(ConsumerRecord<?, ?> messaggio) {
        return messaggio.topic() + "-" + messaggio.partition() + "@" + messaggio.offset();
    }

    private static String causa(Exception errore) {
        Throwable causa = NestedExceptionUtils.getMostSpecificCause(errore);
        return causa.getClass().getSimpleName() + ": " + causa.getMessage();
    }
}
