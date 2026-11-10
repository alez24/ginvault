package it.ginvault.produzione.infrastruttura.eventi;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * I consumer Kafka di Produzione, tutti nel consumer group "produzione"
 * (spring.kafka.consumer.group-id).
 *
 * Due listener separati, uno per topic: ognuno ha i suoi consumer e ritenta
 * per conto suo. Se una ConsegnaRegistrata aspetta il suo piano (l'edizione
 * non è ancora arrivata), quei tentativi non fermano la lettura di
 * edizioni.eventi, da cui arriverà proprio l'EdizioneCreata che serve.
 *
 * idIsGroup = false: l'id dà il nome ai thread e ai log, il gruppo resta
 * "produzione" per tutti e due.
 */
@Component
class ConsumerEventi {

    private final ElaborazioneEventi elaborazione;

    ConsumerEventi(ElaborazioneEventi elaborazione) {
        this.elaborazione = elaborazione;
    }

    @KafkaListener(id = "produzione-edizioni", idIsGroup = false, topics = ConfigurazioneKafka.EDIZIONI)
    void daEdizioni(ConsumerRecord<String, String> messaggio) {
        elaborazione.elabora(messaggio);
    }

    @KafkaListener(id = "produzione-magazzino", idIsGroup = false, topics = ConfigurazioneKafka.MAGAZZINO)
    void daMagazzino(ConsumerRecord<String, String> messaggio) {
        elaborazione.elabora(messaggio);
    }
}
