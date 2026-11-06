package it.ginvault.ricette.api;

import java.util.List;

import it.ginvault.ricette.applicazione.FormulazioneNonTrovata;
import it.ginvault.ricette.dominio.FormulazioneNonValida;
import it.ginvault.ricette.dominio.TransizioneNonConsentita;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduce le eccezioni in risposte HTTP con corpo "Problem Details" (RFC 9457):
 *   {"type", "title", "status", "detail", "instance"}
 * Nessuno stack trace esce dal servizio.
 *
 *   FormulazioneNonTrovata              -> 404
 *   TransizioneNonConsentita            -> 409 (in conflitto con lo stato attuale)
 *   modifica concorrente (blocco ottim.)-> 409
 *   FormulazioneNonValida               -> 422 (forma giusta, contenuto non accettabile)
 *   Bean Validation (@Valid)            -> 400, con l'elenco dei campi sbagliati
 *
 * La classe base (ResponseEntityExceptionHandler) gestisce già gli errori
 * standard di Spring MVC: JSON illeggibile, UUID malformato nel path, metodo
 * non supportato...
 *
 * Volutamente NESSUN @ExceptionHandler(Exception.class): intercetterebbe anche
 * l'AccessDeniedException di @PreAuthorize, che deve arrivare a Spring
 * Security per diventare un 403.
 */
@RestControllerAdvice
class GestoreErrori extends ResponseEntityExceptionHandler {

    @ExceptionHandler(FormulazioneNonTrovata.class)
    ProblemDetail nonTrovata(FormulazioneNonTrovata errore) {
        return problema(HttpStatus.NOT_FOUND, "Formulazione non trovata", errore.getMessage());
    }

    @ExceptionHandler(TransizioneNonConsentita.class)
    ProblemDetail transizione(TransizioneNonConsentita errore) {
        return problema(HttpStatus.CONFLICT, "Operazione non consentita nello stato attuale", errore.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail modificaConcorrente(OptimisticLockingFailureException errore) {
        return problema(HttpStatus.CONFLICT, "Modifica concorrente",
                "La formulazione è stata modificata da un'altra richiesta nello stesso momento: riprova");
    }

    @ExceptionHandler(FormulazioneNonValida.class)
    ProblemDetail nonValida(FormulazioneNonValida errore) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Formulazione non valida", errore.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException errore,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest richiesta) {
        List<CampoNonValido> campi = errore.getBindingResult().getFieldErrors().stream()
                .map(campo -> new CampoNonValido(campo.getField(), campo.getDefaultMessage()))
                .toList();
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Richiesta non valida",
                "Alcuni campi non sono validi");
        problema.setProperty("campi", campi);
        return ResponseEntity.badRequest().body(problema);
    }

    private static ProblemDetail problema(HttpStatus status, String titolo, String dettaglio) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, dettaglio);
        problema.setTitle(titolo);
        return problema;
    }

    record CampoNonValido(String campo, String messaggio) {
    }
}
