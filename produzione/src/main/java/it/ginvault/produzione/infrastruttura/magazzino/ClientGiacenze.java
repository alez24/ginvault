package it.ginvault.produzione.infrastruttura.magazzino;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import it.ginvault.giacenze.v1.AnnullaRiservaRequest;
import it.ginvault.giacenze.v1.AnnullaRiservaResponse;
import it.ginvault.giacenze.v1.DisponibilitaRequest;
import it.ginvault.giacenze.v1.GiacenzeGrpc;
import it.ginvault.giacenze.v1.LottoDisponibile;
import it.ginvault.giacenze.v1.RiservaLottoRequest;
import it.ginvault.giacenze.v1.RiservaLottoResponse;
import it.ginvault.produzione.applicazione.ErroreMagazzino;
import it.ginvault.produzione.applicazione.Giacenze;
import it.ginvault.produzione.applicazione.MagazzinoNonDisponibile;
import it.ginvault.produzione.applicazione.RiservaNonRiuscita;
import it.ginvault.produzione.dominio.LottoProposto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La porta Giacenze realizzata con gRPC (giacenze.proto, modulo giacenze-api).
 *
 * Ogni chiamata ha la sua DEADLINE (2 secondi): oltre, gRPC la interrompe da
 * solo e il magazzino, che vede la chiamata annullata, smette di lavorarci.
 * La deadline è assoluta, quindi si mette sullo stub a ogni chiamata, non una
 * volta per tutte.
 *
 * Status gRPC -> eccezione dell'applicazione -> risposta HTTP (GestoreErrori):
 *   DEADLINE_EXCEEDED, UNAVAILABLE                      -> MagazzinoNonDisponibile -> 503
 *   FAILED_PRECONDITION, ALREADY_EXISTS, NOT_FOUND,
 *   ABORTED                                              -> RiservaNonRiuscita      -> 409
 *   tutti gli altri (INTERNAL, UNAUTHENTICATED...)       -> ErroreMagazzino         -> 502
 */
public class ClientGiacenze implements Giacenze {

    private static final Logger log = LoggerFactory.getLogger(ClientGiacenze.class);

    private final Channel canale;
    private final Duration deadline;

    public ClientGiacenze(Channel canale, Duration deadline) {
        this.canale = canale;
        this.deadline = deadline;
    }

    @Override
    public List<LottoProposto> disponibilita(String botanica) {
        try {
            return stub().disponibilita(DisponibilitaRequest.newBuilder().setBotanica(botanica).build())
                    .getLottiList().stream()
                    .map(ClientGiacenze::lotto)
                    .toList();
        } catch (StatusRuntimeException errore) {
            throw traduci(errore, "Disponibilita");
        }
    }

    @Override
    public Riserva riservaLotto(UUID lottoId, UUID pianoId, BigDecimal kg) {
        RiservaLottoRequest richiesta = RiservaLottoRequest.newBuilder()
                .setLottoId(lottoId.toString())
                .setPianoId(pianoId.toString())
                .setKg(kg.toPlainString())
                .build();
        try {
            RiservaLottoResponse risposta = stub().riservaLotto(richiesta);
            return new Riserva(UUID.fromString(risposta.getLottoId()), UUID.fromString(risposta.getPianoId()),
                    risposta.getOrigine(), new BigDecimal(risposta.getKgRiservati()),
                    new BigDecimal(risposta.getKgDisponibili()), risposta.getGiaRiservato());
        } catch (StatusRuntimeException errore) {
            throw traduci(errore, "RiservaLotto");
        }
    }

    @Override
    public Annullamento annullaRiserva(UUID lottoId, UUID pianoId) {
        AnnullaRiservaRequest richiesta = AnnullaRiservaRequest.newBuilder()
                .setLottoId(lottoId.toString())
                .setPianoId(pianoId.toString())
                .build();
        try {
            AnnullaRiservaResponse risposta = stub().annullaRiserva(richiesta);
            return new Annullamento(new BigDecimal(risposta.getKgLiberati()), risposta.getNessunaRiserva());
        } catch (StatusRuntimeException errore) {
            throw traduci(errore, "AnnullaRiserva");
        }
    }

    private GiacenzeGrpc.GiacenzeBlockingStub stub() {
        return GiacenzeGrpc.newBlockingStub(canale).withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static LottoProposto lotto(LottoDisponibile lotto) {
        return new LottoProposto(UUID.fromString(lotto.getLottoId()), lotto.getBotanica(), lotto.getOrigine(),
                new BigDecimal(lotto.getKg()), new BigDecimal(lotto.getKgDisponibili()),
                LocalDate.parse(lotto.getScadenza()), lotto.getFornitore().isEmpty() ? null : lotto.getFornitore());
    }

    private RuntimeException traduci(StatusRuntimeException errore, String metodo) {
        Status stato = errore.getStatus();
        String descrizione = stato.getDescription() == null ? stato.getCode().name() : stato.getDescription();
        return switch (stato.getCode()) {
            case DEADLINE_EXCEEDED -> {
                log.warn("Giacenze.{}: il magazzino non ha risposto entro {} ms", metodo, deadline.toMillis());
                yield new MagazzinoNonDisponibile("Il magazzino non ha risposto entro " + secondi()
                        + ": riprova fra poco", errore);
            }
            case UNAVAILABLE -> {
                log.warn("Giacenze.{}: magazzino non raggiungibile ({})", metodo, descrizione);
                yield new MagazzinoNonDisponibile("Il magazzino non è raggiungibile: riprova fra poco", errore);
            }
            case FAILED_PRECONDITION, ALREADY_EXISTS, NOT_FOUND, ABORTED ->
                    new RiservaNonRiuscita(stato.getCode().name(), descrizione);
            default -> {
                log.error("Giacenze.{}: errore {} dal magazzino: {}", metodo, stato.getCode(), descrizione);
                yield new ErroreMagazzino("Il magazzino ha risposto con un errore (" + stato.getCode() + ")",
                        errore);
            }
        };
    }

    /** 2000 ms -> "2 secondi". */
    private String secondi() {
        long millis = deadline.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + " secondi" : millis + " ms";
    }
}
