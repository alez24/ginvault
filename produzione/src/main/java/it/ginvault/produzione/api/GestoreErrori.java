package it.ginvault.produzione.api;

import it.ginvault.produzione.applicazione.PianoNonTrovato;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce le eccezioni in risposte HTTP con corpo "Problem Details" (RFC 9457),
 * come negli altri servizi. Nessuno stack trace esce dal servizio.
 *
 *   PianoNonTrovato -> 404
 *   id non valido (?edizione=abc) -> 400 (ResponseEntityExceptionHandler)
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
}
