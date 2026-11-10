package it.ginvault.edizioni.infrastruttura.eventi;

import java.time.Duration;

import it.ginvault.edizioni.applicazione.EdizioneNonTrovata;

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
 * Cosa succede quando un messaggio non si riesce a elaborare. Come in
 * Produzione:
 *
 *  - errori che possono passare (database occupato, l'edizione modificata
 *    nello stesso momento da un ricalcolo manuale): si riprova con attese
 *    crescenti, 1 s, 2 s, 4 s, 8 s (edizioni.consumer.* in application.yml).
 *    Mentre si riprova, gli altri messaggi della stessa partizione aspettano:
 *    l'ordine delle rese di un'edizione non cambia;
 *  - errori che non passano: EventoNonValido (messaggio illeggibile) ed
 *    EdizioneNonTrovata (una resa arriva sempre dopo la sua edizione, che
 *    quindi non arriverà più). Nessun nuovo tentativo;
 *  - finiti i tentativi, il messaggio va nel DEAD LETTER TOPIC edizioni.dlt,
 *    nella stessa partizione, con gli header kafka_dlt-*. Non si perde: lo si
 *    può leggere, correggere e rimandare.
 *
 * Un ricalcolo impossibile (quote sotto le prenotate) NON è un errore: il
 * messaggio è elaborato e diventa RicalcoloRifiutato (ElaborazioneEventi).
 *
 * Spring Boot mette questo gestore in tutti i listener @KafkaListener.
 */
@Configuration
public class ConfigurazioneKafka {

    public static final String PRODUZIONE = "produzione.eventi";
    public static final String DEAD_LETTER = "edizioni.dlt";

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurazioneKafka.class);

    @Bean
    DefaultErrorHandler gestoreErroriKafka(KafkaTemplate<String, String> kafka,
                                           @Value("${edizioni.consumer.tentativi:4}") int tentativi,
                                           @Value("${edizioni.consumer.attesa-iniziale:1s}") Duration iniziale,
                                           @Value("${edizioni.consumer.attesa-massima:8s}") Duration massima) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafka,
                (messaggio, errore) -> new TopicPartition(DEAD_LETTER, messaggio.partition()));
        ExponentialBackOffWithMaxRetries attese = new ExponentialBackOffWithMaxRetries(tentativi);
        attese.setInitialInterval(iniziale.toMillis());
        attese.setMultiplier(2);
        attese.setMaxInterval(massima.toMillis());

        DefaultErrorHandler gestore = new DefaultErrorHandler(deadLetter, attese);
        gestore.addNotRetryableExceptions(EventoNonValido.class, EdizioneNonTrovata.class);
        gestore.setRetryListeners(new RetryListener() {

            @Override
            public void failedDelivery(ConsumerRecord<?, ?> messaggio, Exception errore, int tentativo) {
                LOG.warn("Messaggio {} non elaborato (tentativo {}): {}", dove(messaggio), tentativo,
                        causa(errore));
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> messaggio, Exception errore) {
                LOG.error("Messaggio {} spostato in {}: {}", dove(messaggio), DEAD_LETTER, causa(errore));
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
