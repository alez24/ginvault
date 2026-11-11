package it.ginvault.pagamenti.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.function.Supplier;

import it.ginvault.pagamenti.applicazione.ServizioPagamenti;
import it.ginvault.pagamenti.dominio.EsitoAddebito;
import it.ginvault.pagamenti.dominio.Pagamento;
import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;
import it.ginvault.pagamenti.infrastruttura.eventi.LettoreComandi.Busta;
import it.ginvault.pagamenti.infrastruttura.eventi.LettoreComandi.RichiestaRimborsoV1;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Elabora un comando arrivato da pagamenti.comandi:
 *
 *  1. legge la busta (messaggio sbagliato -> ComandoNonValido -> dead letter);
 *  2. eventId già in comando_elaborato -> comando già elaborato: si scarta,
 *     senza chiamare Stripe;
 *  3. chiama Stripe (addebito o rimborso) FUORI da ogni transazione del
 *     database, con la chiave di idempotenza della prenotazione;
 *  4. in UNA transazione: salva il pagamento, la riga di outbox con l'esito
 *     (ScritturaOutbox) e l'eventId in comando_elaborato.
 *
 * Se il punto 4 fallisce, l'offset non avanza e Kafka ripropone il comando:
 * il punto 3 si ripete, ma Stripe riconosce la chiave di idempotenza e
 * restituisce l'esito di prima (nessun secondo addebito). Se fallisce il
 * punto 3 (Stripe giù), non si è salvato niente e si riprova.
 */
@Component
public class ElaborazioneComandi {

    static final String RICHIESTA_PAGAMENTO = "RichiestaPagamento";
    static final String RICHIESTA_RIMBORSO = "RichiestaRimborso";

    private static final Logger LOG = LoggerFactory.getLogger(ElaborazioneComandi.class);

    private final LettoreComandi lettore;
    private final ComandiElaboratiRepository elaborati;
    private final ServizioPagamenti servizio;
    private final TransactionTemplate transazione;
    private final Clock orologio;

    ElaborazioneComandi(LettoreComandi lettore, ComandiElaboratiRepository elaborati, ServizioPagamenti servizio,
                        TransactionTemplate transazione, Clock orologio) {
        this.lettore = lettore;
        this.elaborati = elaborati;
        this.servizio = servizio;
        this.transazione = transazione;
        this.orologio = orologio;
    }

    public void elabora(ConsumerRecord<String, String> messaggio) {
        Busta busta = lettore.busta(messaggio.value());
        String dove = messaggio.topic() + "-" + messaggio.partition() + "@" + messaggio.offset();
        if (elaborati.existsById(busta.eventId())) {
            LOG.info("{} {} da {}: già elaborato, scartato (consumer idempotente)", busta.tipo(), busta.eventId(),
                    dove);
            return;
        }
        switch (busta.tipo()) {
            case RICHIESTA_PAGAMENTO -> richiestaPagamento(busta, messaggio, dove);
            case RICHIESTA_RIMBORSO -> richiestaRimborso(busta, messaggio, dove);
            default -> throw new ComandoNonValido("Comando " + busta.tipo() + " sconosciuto: Pagamenti legge "
                    + RICHIESTA_PAGAMENTO + " e " + RICHIESTA_RIMBORSO + " (eventId " + busta.eventId() + ")");
        }
    }

    private void richiestaPagamento(Busta busta, ConsumerRecord<String, String> messaggio, String dove) {
        RichiestaDiPagamento richiesta = lettore.richiestaPagamento(busta);
        EsitoAddebito esito = servizio.esitoAddebito(richiesta);
        boolean nuovo = registra(busta, messaggio, () -> servizio.registraAddebito(richiesta, esito));
        if (!nuovo) {
            LOG.info("{} {} da {}: la prenotazione {} ha già un pagamento, esito ripetuto senza chiamare Stripe",
                    busta.tipo(), busta.eventId(), dove, richiesta.prenotazioneId());
            return;
        }
        switch (esito) {
            case EsitoAddebito.Riuscito riuscito -> LOG.info("{} {} da {}: prenotazione {} pagata, {} {} con {} ({})",
                    busta.tipo(), busta.eventId(), dove, richiesta.prenotazioneId(),
                    euro(richiesta.importoCentesimi()), richiesta.valuta(), richiesta.metodo(),
                    riuscito.riferimento());
            case EsitoAddebito.Rifiutato rifiutato -> LOG.info("{} {} da {}: prenotazione {} NON pagata, {} {} con {}"
                            + " ({} {})", busta.tipo(), busta.eventId(), dove, richiesta.prenotazioneId(),
                    euro(richiesta.importoCentesimi()), richiesta.valuta(), richiesta.metodo(), rifiutato.motivo(),
                    rifiutato.codice());
        }
    }

    private void richiestaRimborso(Busta busta, ConsumerRecord<String, String> messaggio, String dove) {
        RichiestaRimborsoV1 comando = lettore.richiestaRimborso(busta);
        String rimborso = servizio.eseguiRimborso(comando.prenotazioneId());
        Pagamento pagamento = registra(busta, messaggio,
                () -> servizio.registraRimborso(comando.prenotazioneId(), rimborso, comando.motivo()));
        LOG.info("{} {} da {}: prenotazione {} rimborsata, {} {} ({}, pagamento {})", busta.tipo(), busta.eventId(),
                dove, comando.prenotazioneId(), euro(pagamento.importoCentesimi()), pagamento.valuta(), rimborso,
                pagamento.riferimentoStripe());
    }

    /** Il lavoro sul database e la riga in comando_elaborato, nello stesso commit. */
    private <T> T registra(Busta busta, ConsumerRecord<String, String> messaggio, Supplier<T> lavoro) {
        return transazione.execute(stato -> {
            T risultato = lavoro.get();
            elaborati.save(new ComandoElaborato(busta.eventId(), busta.tipo(), messaggio.topic(),
                    messaggio.partition(), messaggio.offset(), orologio.instant()));
            return risultato;
        });
    }

    /** 9000 -> "90,00". */
    private static String euro(long centesimi) {
        return BigDecimal.valueOf(centesimi, 2).toPlainString().replace('.', ',');
    }
}
