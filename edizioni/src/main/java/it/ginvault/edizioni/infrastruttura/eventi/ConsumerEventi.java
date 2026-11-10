package it.ginvault.edizioni.infrastruttura.eventi;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Il consumer Kafka di Edizioni, nel consumer group "edizioni"
 * (spring.kafka.consumer.group-id): legge produzione.eventi, cioè le
 * ResaRicalcolata dei piani di produzione.
 *
 * 3 consumer, uno per partizione (spring.kafka.listener.concurrency). La
 * chiave dei messaggi è l'id dell'edizione: le rese di un'edizione arrivano
 * nella stessa partizione, nell'ordine in cui Produzione le ha pubblicate.
 */
@Component
class ConsumerEventi {

    private final ElaborazioneEventi elaborazione;

    ConsumerEventi(ElaborazioneEventi elaborazione) {
        this.elaborazione = elaborazione;
    }

    @KafkaListener(id = "edizioni-produzione", idIsGroup = false, topics = ConfigurazioneKafka.PRODUZIONE)
    void daProduzione(ConsumerRecord<String, String> messaggio) {
        elaborazione.elabora(messaggio);
    }
}
