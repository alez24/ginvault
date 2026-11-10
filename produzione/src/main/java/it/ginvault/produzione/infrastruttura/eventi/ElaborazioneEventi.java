package it.ginvault.produzione.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Clock;

import it.ginvault.produzione.applicazione.NuovoPiano;
import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.dominio.ConsegnaArrivata;
import it.ginvault.produzione.dominio.EsitoConsegna;
import it.ginvault.produzione.infrastruttura.eventi.LettoreEventi.Busta;
import it.ginvault.produzione.infrastruttura.eventi.LettoreEventi.ConsegnaRegistrataV1;
import it.ginvault.produzione.infrastruttura.eventi.LettoreEventi.EdizioneCreataV1;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Elabora un messaggio arrivato da Kafka, in UNA transazione del database:
 *
 *  1. legge la busta (messaggio sbagliato -> EventoNonValido -> dead letter);
 *  2. eventId già in evento_elaborato -> evento già elaborato: si scarta;
 *  3. applica l'evento (crea il piano, o ricalcola la resa: in quel caso
 *     ScritturaOutbox aggiunge la riga di ResaRicalcolata);
 *  4. scrive l'eventId in evento_elaborato.
 *
 * Tutto nello stesso commit. Se qualcosa fallisce non resta niente, l'offset
 * non avanza e Kafka ripropone il messaggio (ConfigurazioneKafka decide
 * quante volte). Gli eventi di tipo sconosciuto si ignorano, senza errori: su
 * edizioni.eventi arriveranno anche eventi che a Produzione non servono.
 */
@Component
public class ElaborazioneEventi {

    private static final Logger LOG = LoggerFactory.getLogger(ElaborazioneEventi.class);

    private final LettoreEventi lettore;
    private final EventiElaboratiRepository elaborati;
    private final ServizioProduzione servizio;
    private final Clock orologio;

    ElaborazioneEventi(LettoreEventi lettore, EventiElaboratiRepository elaborati, ServizioProduzione servizio,
                       Clock orologio) {
        this.lettore = lettore;
        this.elaborati = elaborati;
        this.servizio = servizio;
        this.orologio = orologio;
    }

    @Transactional
    public void elabora(ConsumerRecord<String, String> messaggio) {
        Busta busta = lettore.busta(messaggio.value());
        String dove = messaggio.topic() + "-" + messaggio.partition() + "@" + messaggio.offset();
        if (elaborati.existsById(busta.eventId())) {
            LOG.info("{} {} da {}: già elaborato, scartato (consumer idempotente)", busta.tipo(), busta.eventId(),
                    dove);
            return;
        }
        boolean elaborato = switch (messaggio.topic() + "/" + busta.tipo()) {
            case ConfigurazioneKafka.EDIZIONI + "/EdizioneCreata" -> edizioneCreata(busta, dove);
            case ConfigurazioneKafka.MAGAZZINO + "/ConsegnaRegistrata" -> consegnaRegistrata(busta, dove);
            default -> false;
        };
        if (elaborato) {
            elaborati.save(new EventoElaborato(busta.eventId(), busta.tipo(), messaggio.topic(),
                    messaggio.partition(), messaggio.offset(), orologio.instant()));
        } else {
            LOG.debug("{} {} da {}: non interessa a Produzione, ignorato", busta.tipo(), busta.eventId(), dove);
        }
    }

    private boolean edizioneCreata(Busta busta, String dove) {
        EdizioneCreataV1 evento = lettore.edizioneCreata(busta);
        boolean nuovo = servizio.creaPiano(new NuovoPiano(evento.edizioneId(), evento.formulazione().id(),
                evento.formulazione().versione(), evento.botanicaDiStagione(), evento.grammiPerBottiglia(),
                evento.origineBotanica(), evento.kgPrevisti(), evento.tiratura()));
        if (nuovo) {
            LOG.info("EdizioneCreata {} da {}: piano di produzione per l'edizione {} ({} kg di {}, {})",
                    busta.eventId(), dove, evento.edizioneId(), kg(evento.kgPrevisti()), evento.botanicaDiStagione(),
                    evento.origineBotanica());
        } else {
            LOG.info("EdizioneCreata {} da {}: il piano dell'edizione {} c'è già, nessun cambiamento",
                    busta.eventId(), dove, evento.edizioneId());
        }
        return true;
    }

    private boolean consegnaRegistrata(Busta busta, String dove) {
        ConsegnaRegistrataV1 evento = lettore.consegnaRegistrata(busta);
        EsitoConsegna esito = servizio.registraConsegna(evento.edizioneId(), new ConsegnaArrivata(evento.ordineId(),
                evento.lottoId(), evento.botanica(), evento.origine(), evento.kgOrdinati(), evento.kgArrivati(),
                evento.esitoQualita(), evento.consegnataIl()));
        LOG.info("ConsegnaRegistrata {} da {}: ordine {} per l'edizione {}, {} kg di {} su {} -> {}",
                busta.eventId(), dove, evento.ordineId(), evento.edizioneId(), kg(evento.kgArrivati()),
                evento.botanica(), kg(evento.kgOrdinati()), esito);
        return true;
    }

    /** 10.800 -> "10,8". */
    private static String kg(BigDecimal kg) {
        return kg.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
