package it.ginvault.magazzino.api;

import java.util.List;

import it.ginvault.magazzino.applicazione.FornitoreNonTrovato;
import it.ginvault.magazzino.applicazione.LottoNonTrovato;
import it.ginvault.magazzino.applicazione.OrdineNonTrovato;
import it.ginvault.magazzino.dominio.ConsegnaNonValida;
import it.ginvault.magazzino.dominio.OrdineGiaConsegnato;
import it.ginvault.magazzino.dominio.OrdineNonValido;

import org.springframework.dao.DataIntegrityViolationException;
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
 * Traduce le eccezioni in risposte HTTP con corpo "Problem Details" (RFC 9457),
 * come in Edizioni e Ricette. Nessuno stack trace esce dal servizio.
 *
 *   OrdineNonTrovato, LottoNonTrovato, FornitoreNonTrovato -> 404
 *   OrdineGiaConsegnato                     -> 409 (una sola consegna per ordine)
 *   modifica concorrente (blocco ottimistico,
 *     o un secondo lotto per lo stesso ordine) -> 409
 *   OrdineNonValido, ConsegnaNonValida      -> 422 (regole di dominio)
 *   Bean Validation (@Valid)                -> 400, con l'elenco dei campi sbagliati
 *
 * Volutamente NESSUN @ExceptionHandler(Exception.class): intercetterebbe anche
 * l'AccessDeniedException di @PreAuthorize, che deve diventare un 403.
 */
@RestControllerAdvice
class GestoreErrori extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OrdineNonTrovato.class)
    ProblemDetail ordineNonTrovato(OrdineNonTrovato errore) {
        return problema(HttpStatus.NOT_FOUND, "Ordine non trovato", errore.getMessage());
    }

    @ExceptionHandler(LottoNonTrovato.class)
    ProblemDetail lottoNonTrovato(LottoNonTrovato errore) {
        return problema(HttpStatus.NOT_FOUND, "Lotto non trovato", errore.getMessage());
    }

    @ExceptionHandler(FornitoreNonTrovato.class)
    ProblemDetail fornitoreNonTrovato(FornitoreNonTrovato errore) {
        return problema(HttpStatus.NOT_FOUND, "Fornitore non trovato", errore.getMessage());
    }

    @ExceptionHandler(OrdineGiaConsegnato.class)
    ProblemDetail giaConsegnato(OrdineGiaConsegnato errore) {
        return problema(HttpStatus.CONFLICT, "Ordine già consegnato", errore.getMessage());
    }

    /**
     * Due consegne dello stesso ordine nello stesso istante: la seconda trova
     * la revisione cambiata (blocco ottimistico) o il lotto già presente
     * (UNIQUE su ordine_id). In entrambi i casi non è stato salvato niente.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail modificaConcorrente(RuntimeException errore) {
        return problema(HttpStatus.CONFLICT, "Modifica concorrente",
                "L'ordine o il fornitore sono stati modificati da un'altra richiesta nello stesso momento:"
                        + " ricarica e riprova");
    }

    @ExceptionHandler(OrdineNonValido.class)
    ProblemDetail ordineNonValido(OrdineNonValido errore) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Ordine non valido", errore.getMessage());
    }

    @ExceptionHandler(ConsegnaNonValida.class)
    ProblemDetail consegnaNonValida(ConsegnaNonValida errore) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Consegna non valida", errore.getMessage());
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
