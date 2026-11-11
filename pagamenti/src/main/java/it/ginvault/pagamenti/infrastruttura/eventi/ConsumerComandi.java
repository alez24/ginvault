package it.ginvault.pagamenti.infrastruttura.eventi;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Il consumer Kafka di Pagamenti, nel consumer group "pagamenti"
 * (spring.kafka.consumer.group-id): legge i comandi che Edizioni manda su
 * pagamenti.comandi (RichiestaPagamento, RichiestaRimborso).
 *
 * I comandi hanno come chiave l'id della prenotazione: quelli della stessa
 * prenotazione stanno nella stessa partizione e arrivano in ordine (il
 * rimborso dopo il suo pagamento), a un solo consumer alla volta.
 *
 * idIsGroup = false: l'id dà il nome ai thread e ai log, il gruppo resta "pagamenti".
 */
@Component
class ConsumerComandi {

    private final ElaborazioneComandi elaborazione;

    ConsumerComandi(ElaborazioneComandi elaborazione) {
        this.elaborazione = elaborazione;
    }

    @KafkaListener(id = "pagamenti-comandi", idIsGroup = false, topics = ConfigurazioneKafka.COMANDI)
    void comando(ConsumerRecord<String, String> messaggio) {
        elaborazione.elabora(messaggio);
    }
}
