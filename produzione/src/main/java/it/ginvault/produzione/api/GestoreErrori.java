package it.ginvault.produzione.api;

import it.ginvault.produzione.applicazione.ErroreMagazzino;
import it.ginvault.produzione.applicazione.MagazzinoNonDisponibile;
import it.ginvault.produzione.applicazione.PianoNonTrovato;
import it.ginvault.produzione.applicazione.RiservaNonRiuscita;
import it.ginvault.produzione.dominio.IntegrazioneRifiutata;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce le eccezioni in risposte HTTP con corpo "Problem Details" (RFC 9457),
 * come negli altri servizi. Nessuno stack trace esce dal servizio.
 *
 *   PianoNonTrovato -> 404
 *   id non valido (?edizione=abc), corpo non valido -> 400 (ResponseEntityExceptionHandler)
 *   IntegrazioneRifiutata (regola del piano) -> 409, con "motivo" e i dettagli
 *   RiservaNonRiuscita (regola del magazzino) -> 409, motivo RISERVA_RIFIUTATA
 *   modifica concorrente dello stesso piano -> 409
 *   MagazzinoNonDisponibile (deadline, irraggiungibile) -> 503 con Retry-After
 *   ErroreMagazzino -> 502
 *
 * Volutamente NESSUN @ExceptionHandler(Exception.class): intercetterebbe anche
 * l'AccessDeniedException di @PreAuthorize, che deve diventare un 403.
 */
@RestControllerAdvice
class GestoreErrori extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PianoNonTrovato.class)
    ProblemDetail pianoNonTrovato(PianoNonTrovato errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, errore.getMessage());
        problema.setTitle("Piano non trovato");
        return problema;
    }

    /** Es. motivo ORIGINE_DIVERSA, con origineRichiesta e origineLotto: il front-end spiega il rifiuto. */
    @ExceptionHandler(IntegrazioneRifiutata.class)
    ProblemDetail integrazioneRifiutata(IntegrazioneRifiutata errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, errore.getMessage());
        problema.setTitle("Integrazione rifiutata");
        problema.setProperty("motivo", errore.motivo().name());
        errore.dettagli().forEach(problema::setProperty);
        return problema;
    }

    @ExceptionHandler(RiservaNonRiuscita.class)
    ProblemDetail riservaNonRiuscita(RiservaNonRiuscita errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, errore.getMessage());
        problema.setTitle("Riserva rifiutata dal magazzino");
        problema.setProperty("motivo", "RISERVA_RIFIUTATA");
        problema.setProperty("statoGrpc", errore.statoGrpc());
        return problema;
    }

    /** Due modifiche allo stesso piano nello stesso istante (@Version): la seconda si ripete. */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail modificaConcorrente(RuntimeException errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Il piano è stato modificato nello stesso momento da un'altra richiesta: riprova");
        problema.setTitle("Modifica concorrente");
        return problema;
    }

    /** Il magazzino non risponde in 2 secondi o è giù: riprovare fra poco ha senso (Retry-After). */
    @ExceptionHandler(MagazzinoNonDisponibile.class)
    ResponseEntity<ProblemDetail> magazzinoNonDisponibile(MagazzinoNonDisponibile errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, errore.getMessage());
        problema.setTitle("Magazzino non disponibile");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problema);
    }

    @ExceptionHandler(ErroreMagazzino.class)
    ProblemDetail erroreMagazzino(ErroreMagazzino errore) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, errore.getMessage());
        problema.setTitle("Errore del magazzino");
        return problema;
    }
}
