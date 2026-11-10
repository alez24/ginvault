package it.ginvault.magazzino.infrastruttura.grpc;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import it.ginvault.giacenze.v1.DisponibilitaRequest;
import it.ginvault.giacenze.v1.DisponibilitaResponse;
import it.ginvault.giacenze.v1.GiacenzeGrpc;
import it.ginvault.giacenze.v1.LottoDisponibile;
import it.ginvault.giacenze.v1.RiservaLottoRequest;
import it.ginvault.giacenze.v1.RiservaLottoResponse;
import it.ginvault.magazzino.applicazione.LottoNonTrovato;
import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.applicazione.Viste.DisponibilitaVista;
import it.ginvault.magazzino.applicazione.Viste.LottoDisponibileVista;
import it.ginvault.magazzino.applicazione.Viste.RiservaVista;
import it.ginvault.magazzino.dominio.OrdineNonValido;
import it.ginvault.magazzino.dominio.RiservaRifiutata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Il servizio gRPC Giacenze (giacenze.proto): un "adapter" sottile come i
 * controller REST. Traduce i messaggi protobuf in chiamate a
 * ServizioApprovvigionamento e le eccezioni in status gRPC:
 *
 *   id o kg non validi, botanica mancante           -> INVALID_ARGUMENT
 *   LottoNonTrovato                                  -> NOT_FOUND
 *   lotto non utilizzabile, kg insufficienti         -> FAILED_PRECONDITION
 *   il piano ha già riservato altri kg del lotto     -> ALREADY_EXISTS
 *   riserva concorrente sullo stesso lotto           -> ABORTED (si può ripetere)
 *   altro                                            -> INTERNAL, senza dettagli
 *
 * Arriva qui solo chi ha passato AutenticazioneGrpc.
 */
public class ServizioGiacenze extends GiacenzeGrpc.GiacenzeImplBase {

    private static final Logger log = LoggerFactory.getLogger(ServizioGiacenze.class);

    private final ServizioApprovvigionamento servizio;

    public ServizioGiacenze(ServizioApprovvigionamento servizio) {
        this.servizio = servizio;
    }

    @Override
    public void disponibilita(DisponibilitaRequest richiesta, StreamObserver<DisponibilitaResponse> risposta) {
        rispondi(risposta, () -> {
            DisponibilitaVista vista = servizio.disponibilita(richiesta.getBotanica());
            DisponibilitaResponse.Builder messaggio = DisponibilitaResponse.newBuilder()
                    .setBotanica(vista.botanica())
                    .setKgDisponibiliTotali(vista.kgDisponibiliTotali().toPlainString());
            vista.lotti().forEach(lotto -> messaggio.addLotti(lotto(lotto)));
            return messaggio.build();
        });
    }

    @Override
    public void riservaLotto(RiservaLottoRequest richiesta, StreamObserver<RiservaLottoResponse> risposta) {
        rispondi(risposta, () -> {
            UUID lottoId = uuid(richiesta.getLottoId(), "lotto_id");
            UUID pianoId = uuid(richiesta.getPianoId(), "piano_id");
            BigDecimal kg = kg(richiesta.getKg());
            // Chi ha chiamato ha già smesso di aspettare (deadline scaduta):
            // inutile riservare per una risposta che nessuno leggerà
            if (Context.current().isCancelled()) {
                throw Status.CANCELLED.withDescription("Chiamata annullata da chi l'ha fatta").asRuntimeException();
            }
            String chi = AutenticazioneGrpc.utente().getName();
            RiservaVista vista = servizio.riservaLotto(lottoId, pianoId, kg, chi);
            if (vista.giaRiservato()) {
                log.info("Riserva già presente: {} kg del lotto {} per il piano {} (richiesta ripetuta da {})",
                        vista.kgRiservati(), lottoId, pianoId, chi);
            } else {
                log.info("Riservati {} kg del lotto {} ({}) per il piano {}, chiesti da {}: liberi {} kg",
                        vista.kgRiservati(), lottoId, vista.origine(), pianoId, chi, vista.kgDisponibili());
            }
            return RiservaLottoResponse.newBuilder()
                    .setLottoId(vista.lottoId().toString())
                    .setPianoId(vista.pianoId().toString())
                    .setBotanica(vista.botanica())
                    .setOrigine(vista.origine())
                    .setKgRiservati(vista.kgRiservati().toPlainString())
                    .setKgDisponibili(vista.kgDisponibili().toPlainString())
                    .setGiaRiservato(vista.giaRiservato())
                    .build();
        });
    }

    private static LottoDisponibile lotto(LottoDisponibileVista lotto) {
        LottoDisponibile.Builder messaggio = LottoDisponibile.newBuilder()
                .setLottoId(lotto.id().toString())
                .setBotanica(lotto.botanica())
                .setOrigine(lotto.origine())
                .setKg(lotto.kg().toPlainString())
                .setKgDisponibili(lotto.kgDisponibili().toPlainString())
                .setScadenza(lotto.scadenza().toString());
        if (lotto.fornitore() != null) {
            messaggio.setFornitoreId(lotto.fornitore().id().toString()).setFornitore(lotto.fornitore().nome());
        }
        return messaggio.build();
    }

    /** Una risposta unaria: o un messaggio e onCompleted, o un solo onError con lo status giusto. */
    private static <T> void rispondi(StreamObserver<T> risposta, Supplier<T> lavoro) {
        T messaggio;
        try {
            messaggio = lavoro.get();
        } catch (StatusRuntimeException errore) {
            risposta.onError(errore);
            return;
        } catch (RuntimeException errore) {
            risposta.onError(stato(errore).asRuntimeException());
            return;
        }
        risposta.onNext(messaggio);
        risposta.onCompleted();
    }

    static Status stato(RuntimeException errore) {
        return switch (errore) {
            case RiservaRifiutata rifiuto -> (switch (rifiuto.motivo()) {
                case DATI_NON_VALIDI -> Status.INVALID_ARGUMENT;
                case NON_UTILIZZABILE, KG_INSUFFICIENTI -> Status.FAILED_PRECONDITION;
                case GIA_RISERVATO -> Status.ALREADY_EXISTS;
            }).withDescription(rifiuto.getMessage());
            case LottoNonTrovato nonTrovato -> Status.NOT_FOUND.withDescription(nonTrovato.getMessage());
            case OrdineNonValido nonValido -> Status.INVALID_ARGUMENT.withDescription(nonValido.getMessage());
            case OptimisticLockingFailureException concorrente -> aborted();
            case DataIntegrityViolationException concorrente -> aborted();
            default -> {
                log.error("Errore inatteso in una chiamata gRPC", errore);
                yield Status.INTERNAL.withDescription("Errore interno del magazzino");
            }
        };
    }

    private static Status aborted() {
        return Status.ABORTED.withDescription("Un'altra riserva sullo stesso lotto nello stesso momento: riprova");
    }

    private static UUID uuid(String testo, String campo) {
        try {
            return UUID.fromString(testo);
        } catch (IllegalArgumentException errore) {
            throw Status.INVALID_ARGUMENT.withDescription(campo + " non è un UUID: \"" + testo + "\"")
                    .asRuntimeException();
        }
    }

    private static BigDecimal kg(String testo) {
        try {
            return new BigDecimal(testo);
        } catch (NumberFormatException errore) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("kg deve essere un numero decimale con il punto (es. \"1.200\"): \"" + testo + "\"")
                    .asRuntimeException();
        }
    }
}
