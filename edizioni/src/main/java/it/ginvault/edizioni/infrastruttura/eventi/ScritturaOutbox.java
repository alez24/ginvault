package it.ginvault.edizioni.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.edizioni.dominio.Canale;
import it.ginvault.edizioni.dominio.EdizioneCreata;
import it.ginvault.edizioni.dominio.PagamentoRichiesto;
import it.ginvault.edizioni.dominio.PrenotazioneAnnullata;
import it.ginvault.edizioni.dominio.PrenotazioneConfermata;
import it.ginvault.edizioni.dominio.Quota;
import it.ginvault.edizioni.dominio.RicalcoloRifiutato;
import it.ginvault.edizioni.dominio.RimborsoRichiesto;
import it.ginvault.edizioni.dominio.TiraturaAumentata;
import it.ginvault.edizioni.dominio.TiraturaRidotta;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prima metà della TRANSACTIONAL OUTBOX (come in Magazzino): trasforma gli
 * eventi di dominio di Edizioni in messaggi (la busta JSON di docs/eventi.md)
 * e li scrive nella tabella outbox, dentro la transazione del salvataggio
 * (phase = BEFORE_COMMIT). Edizione e riga di outbox entrano nello stesso
 * commit: o ci sono entrambe, o nessuna.
 *
 * Su edizioni.eventi la chiave del messaggio è l'id dell'edizione: tutti gli
 * eventi di un'edizione finiscono nella stessa partizione, nell'ordine in cui
 * nascono.
 *
 * Gli eventi: EdizioneCreata, TiraturaRidotta, TiraturaAumentata e
 * RicalcoloRifiutato. Gli ultimi tre nascono quasi sempre da una
 * ResaRicalcolata di Produzione (ElaborazioneEventi): in quel caso la riga di
 * outbox entra nello stesso commit dell'edizione ricalcolata e della riga di
 * evento_elaborato. Nascono anche dal ricalcolo manuale del distillatore.
 *
 * La SAGA del pagamento (storia di Giulia):
 *  - PrenotazioneConfermata e PrenotazioneAnnullata su edizioni.eventi;
 *  - i COMANDI RichiestaPagamento e RichiestaRimborso su pagamenti.comandi,
 *    con chiave l'id della prenotazione (il formato lo fissa Pagamenti).
 * Anche un comando passa dall'outbox: se Kafka è giù il pagamento parte
 * quando torna, e un comando non parte mai per una prenotazione non salvata.
 */
@Component
public class ScritturaOutbox {

    public static final String TOPIC = "edizioni.eventi";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ScritturaOutbox(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void edizioneCreata(EdizioneCreata evento) {
        EdizioneCreataV1 payload = new EdizioneCreataV1(evento.edizioneId(),
                new FormulazioneV1(evento.formulazione().id(), evento.formulazione().versione()),
                evento.botanicaDiStagione(), evento.grammiPerBottiglia(), evento.origineBotanica(),
                evento.kgPrevisti(), evento.tiratura(), evento.quote());
        scrivi("EdizioneCreata", 1, evento.edizioneId(), evento.occorsoIl(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void tiraturaRidotta(TiraturaRidotta evento) {
        scrivi("TiraturaRidotta", 1, evento.edizioneId(), evento.avvenutaIl(),
                new TiraturaCambiataV1(evento.edizioneId(), evento.tiraturaVecchia(), evento.tiraturaNuova(),
                        evento.quote(), evento.kgDisponibili(), evento.causa(), evento.resaRicalcolataId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void tiraturaAumentata(TiraturaAumentata evento) {
        scrivi("TiraturaAumentata", 1, evento.edizioneId(), evento.avvenutaIl(),
                new TiraturaCambiataV1(evento.edizioneId(), evento.tiraturaVecchia(), evento.tiraturaNuova(),
                        evento.quote(), evento.kgDisponibili(), evento.causa(), evento.resaRicalcolataId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void ricalcoloRifiutato(RicalcoloRifiutato evento) {
        Map<Canale, QuotaV1> quote = new EnumMap<>(Canale.class);
        evento.quote().forEach((canale, quota) -> quote.put(canale, QuotaV1.di(quota)));
        scrivi("RicalcoloRifiutato", 1, evento.edizioneId(), evento.avvenutaIl(),
                new RicalcoloRifiutatoV1(evento.edizioneId(), evento.motivo().name(), evento.spiegazione(),
                        evento.tiratura(), evento.tiraturaRichiesta(), evento.kgDisponibili(), evento.kgRichiesti(),
                        quote, evento.causa(), evento.resaRicalcolataId()));
    }

    // ---------------------------------------------------------------- la SAGA del pagamento

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void pagamentoRichiesto(PagamentoRichiesto evento) {
        scrivi(ConfigurazioneKafka.COMANDI_PAGAMENTI, evento.prenotazioneId(), "RichiestaPagamento", 1,
                evento.occorsoIl(), new RichiestaPagamentoV1(evento.prenotazioneId(), evento.edizioneId(),
                        evento.acquirente(), evento.importoCentesimi(), evento.valuta(), evento.metodo(),
                        evento.descrizione()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void rimborsoRichiesto(RimborsoRichiesto evento) {
        scrivi(ConfigurazioneKafka.COMANDI_PAGAMENTI, evento.prenotazioneId(), "RichiestaRimborso", 1,
                evento.occorsoIl(), new RichiestaRimborsoV1(evento.prenotazioneId(), evento.motivo()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void prenotazioneConfermata(PrenotazioneConfermata evento) {
        scrivi(TOPIC, evento.edizioneId(), "PrenotazioneConfermata", 1, evento.occorsoIl(),
                new PrenotazioneConfermataV1(evento.prenotazioneId(), evento.edizioneId(), evento.acquirente(),
                        evento.canale(), evento.bottiglie(), evento.numeri(), evento.totale(),
                        evento.pagamentoId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void prenotazioneAnnullata(PrenotazioneAnnullata evento) {
        scrivi(TOPIC, evento.edizioneId(), "PrenotazioneAnnullata", 1, evento.occorsoIl(),
                new PrenotazioneAnnullataV1(evento.prenotazioneId(), evento.edizioneId(), evento.acquirente(),
                        evento.canale(), evento.bottiglie(), evento.causa().name(), evento.spiegazione()));
    }

    private void scrivi(String tipo, int versione, UUID edizioneId, Instant occorsoIl, Object payload) {
        scrivi(TOPIC, edizioneId, tipo, versione, occorsoIl, payload);
    }

    private void scrivi(String topic, UUID chiave, String tipo, int versione, Instant occorsoIl, Object payload) {
        UUID eventId = UUID.randomUUID();
        Busta busta = new Busta(eventId, tipo, versione, occorsoIl, payload);
        try {
            outbox.save(new RigaOutbox(eventId, topic, chiave.toString(), tipo, json.writeValueAsString(busta),
                    occorsoIl));
        } catch (JsonProcessingException errore) {
            throw new IllegalStateException("Messaggio " + tipo + " non serializzabile", errore);
        }
    }

    /** La busta uguale per tutti gli eventi (docs/eventi.md). */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, Object payload) {
    }

    /**
     * Il payload di EdizioneCreata, versione 1: il CONTRATTO con Produzione (e
     * più avanti Notifiche), separato dal record di dominio.
     *
     * @param kgPrevisti i kg di botanica di stagione su cui è calcolata la tiratura
     */
    record EdizioneCreataV1(UUID edizioneId, FormulazioneV1 formulazione, String botanicaDiStagione,
                            BigDecimal grammiPerBottiglia, String origineBotanica, BigDecimal kgPrevisti,
                            int tiratura, Map<Canale, Integer> quote) {
    }

    record FormulazioneV1(UUID id, int versione) {
    }

    /**
     * Il payload di TiraturaRidotta e di TiraturaAumentata, versione 1: lo
     * stesso per tutti e due, cambia il tipo nella busta. Lo leggerà Notifiche
     * per avvisare chi ha prenotato.
     *
     * @param quote             le quote nuove per canale
     * @param causa             in italiano: il motivo della resa di Produzione o "ricalcolo manuale del distillatore"
     * @param resaRicalcolataId l'eventId della ResaRicalcolata che l'ha causato; null per un ricalcolo manuale
     */
    record TiraturaCambiataV1(UUID edizioneId, int tiraturaPrima, int tiratura, Map<Canale, Integer> quote,
                              BigDecimal kgDisponibili, String causa, UUID resaRicalcolataId) {
    }

    /**
     * Il payload di RicalcoloRifiutato, versione 1: l'edizione è rimasta com'era.
     *
     * @param motivo            QUOTE_NON_RIDUCIBILI o BOTANICA_INSUFFICIENTE
     * @param tiraturaRichiesta quella che verrebbe con i kg della resa (0: nemmeno una bottiglia)
     * @param quote             le quote dell'edizione, con le prenotate: il perché del rifiuto
     */
    record RicalcoloRifiutatoV1(UUID edizioneId, String motivo, String spiegazione, int tiratura,
                                int tiraturaRichiesta, BigDecimal kgDisponibili, BigDecimal kgRichiesti,
                                Map<Canale, QuotaV1> quote, String causa, UUID resaRicalcolataId) {
    }

    record QuotaV1(int bottiglie, int prenotate) {

        static QuotaV1 di(Quota quota) {
            return new QuotaV1(quota.bottiglie(), quota.prenotate());
        }
    }

    /**
     * Il comando RichiestaPagamento, versione 1: il contratto lo fissa
     * Pagamenti (docs/eventi.md). Importo in centesimi: 2 bottiglie a 45,00 -> 9000.
     */
    record RichiestaPagamentoV1(UUID prenotazioneId, UUID edizioneId, String acquirente, long importoCentesimi,
                                String valuta, String metodo, String descrizione) {
    }

    /** Il comando RichiestaRimborso, versione 1: si restituisce tutto l'addebito della prenotazione. */
    record RichiestaRimborsoV1(UUID prenotazioneId, String motivo) {
    }

    /**
     * Il payload di PrenotazioneConfermata, versione 1. Lo leggerà Notifiche
     * per dire a chi ha prenotato i suoi numeri di bottiglia.
     *
     * @param numeriBottiglia progressivi nell'edizione, in ordine: [112, 113]
     */
    record PrenotazioneConfermataV1(UUID prenotazioneId, UUID edizioneId, String acquirente, Canale canale,
                                    int bottiglie, List<Integer> numeriBottiglia, BigDecimal totale,
                                    UUID pagamentoId) {
    }

    /**
     * Il payload di PrenotazioneAnnullata, versione 1: le bottiglie sono
     * tornate libere nella quota del canale.
     *
     * @param causa PAGAMENTO_RIFIUTATO o SCADUTA
     */
    record PrenotazioneAnnullataV1(UUID prenotazioneId, UUID edizioneId, String acquirente, Canale canale,
                                   int bottiglie, String causa, String spiegazione) {
    }
}
