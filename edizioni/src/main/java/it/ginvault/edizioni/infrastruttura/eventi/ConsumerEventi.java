package it.ginvault.edizioni.infrastruttura.eventi;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * I consumer Kafka di Edizioni, nel consumer group "edizioni"
 * (spring.kafka.consumer.group-id), un listener per topic:
 *  - produzione.eventi: le ResaRicalcolata dei piani di produzione. Chiave
 *    l'id dell'edizione: le rese di un'edizione arrivano nella stessa
 *    partizione, nell'ordine in cui Produzione le ha pubblicate;
 *  - pagamenti.esiti: gli esiti dei comandi mandati a Pagamenti (la SAGA).
 *    Chiave l'id della prenotazione: il rimborso arriva dopo il suo pagamento.
 *
 * 3 consumer per listener, uno per partizione (spring.kafka.listener.concurrency).
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

    @KafkaListener(id = "edizioni-pagamenti", idIsGroup = false, topics = ConfigurazioneKafka.PAGAMENTI)
    void daPagamenti(ConsumerRecord<String, String> messaggio) {
        elaborazione.elabora(messaggio);
    }
}
