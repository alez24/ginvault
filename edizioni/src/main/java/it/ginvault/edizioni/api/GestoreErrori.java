package it.ginvault.edizioni.api;

import java.util.List;

import it.ginvault.edizioni.applicazione.EdizioneNonTrovata;
import it.ginvault.edizioni.applicazione.PrenotazioneNonTrovata;
import it.ginvault.edizioni.dominio.CatalogoRicetteNonDisponibile;
import it.ginvault.edizioni.dominio.EdizioneNonValida;
import it.ginvault.edizioni.dominio.FormulazioneGiaUsata;
import it.ginvault.edizioni.dominio.FormulazioneNonUtilizzabile;
import it.ginvault.edizioni.dominio.PrenotazioneNonPossibile;
import it.ginvault.edizioni.dominio.QuotaEsaurita;
import it.ginvault.edizioni.dominio.TiraturaNonRiducibile;

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
 *   EdizioneNonTrovata               -> 404
 *   PrenotazioneNonTrovata           -> 404 (anche quella di un altro)
 *   FormulazioneGiaUsata             -> 409 (una formulazione usata non si riusa)
 *   TiraturaNonRiducibile            -> 409 (toccherebbe SOCI o le prenotate, con la spiegazione)
 *   QuotaEsaurita                    -> 409 (bottiglie libere insufficienti nel canale), motivo QUOTA_ESAURITA
 *   PrenotazioneNonPossibile         -> 409, motivo PRELAZIONE_DEI_SOCI o LIMITE_PER_PERSONA
 *   modifica concorrente (blocco ottim.)-> 409
 *   FormulazioneNonUtilizzabile      -> 422 (inesistente, non scelta, versione sbagliata)
 *   EdizioneNonValida                -> 422 (regole dell'edizione)
 *   CatalogoRicetteNonDisponibile    -> 503 + Retry-After (Ricette spento o lento)
 *   Bean Validation (@Valid)         -> 400, con l'elenco dei campi sbagliati
 *
 * Volutamente NESSUN @ExceptionHandler(Exception.class): intercetterebbe anche
 * l'AccessDeniedException di @PreAuthorize, che deve arrivare a Spring
 * Security per diventare un 403.
 */
@RestControllerAdvice
class GestoreErrori extends ResponseEntityExceptionHandler {

    /** Dopo quanti secondi ha senso riprovare se Ricette non risponde (header Retry-After). */
    private static final String RIPROVA_TRA_SECONDI = "5";

    @ExceptionHandler(EdizioneNonTrovata.class)
    ProblemDetail nonTrovata(EdizioneNonTrovata errore) {
        return problema(HttpStatus.NOT_FOUND, "Edizione non trovata", errore.getMessage());
    }

    @ExceptionHandler(PrenotazioneNonTrovata.class)
    ProblemDetail prenotazioneNonTrovata(PrenotazioneNonTrovata errore) {
        return problema(HttpStatus.NOT_FOUND, "Prenotazione non trovata", errore.getMessage());
    }

    @ExceptionHandler(FormulazioneGiaUsata.class)
    ProblemDetail giaUsata(FormulazioneGiaUsata errore) {
        return problema(HttpStatus.CONFLICT, "Formulazione già usata", errore.getMessage());
    }

    @ExceptionHandler(TiraturaNonRiducibile.class)
    ProblemDetail nonRiducibile(TiraturaNonRiducibile errore) {
        return problema(HttpStatus.CONFLICT, "Tiratura non riducibile", errore.getMessage());
    }

    /** Il motivo serve al front-end per scegliere cosa mostrare (come ORIGINE_DIVERSA in Produzione). */
    @ExceptionHandler(QuotaEsaurita.class)
    ProblemDetail quotaEsaurita(QuotaEsaurita errore) {
        ProblemDetail problema = problema(HttpStatus.CONFLICT, "Quota esaurita", errore.getMessage());
        problema.setProperty("motivo", "QUOTA_ESAURITA");
        return problema;
    }

    @ExceptionHandler(PrenotazioneNonPossibile.class)
    ProblemDetail prenotazioneNonPossibile(PrenotazioneNonPossibile errore) {
        ProblemDetail problema = problema(HttpStatus.CONFLICT, "Prenotazione non possibile", errore.getMessage());
        problema.setProperty("motivo", errore.motivo().name());
        return problema;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail modificaConcorrente(OptimisticLockingFailureException errore) {
        return problema(HttpStatus.CONFLICT, "Modifica concorrente",
                "L'edizione è stata modificata da un'altra richiesta nello stesso momento: riprova");
    }

    @ExceptionHandler(FormulazioneNonUtilizzabile.class)
    ProblemDetail nonUtilizzabile(FormulazioneNonUtilizzabile errore) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Formulazione non utilizzabile", errore.getMessage());
    }

    @ExceptionHandler(EdizioneNonValida.class)
    ProblemDetail nonValida(EdizioneNonValida errore) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Edizione non valida", errore.getMessage());
    }

    @ExceptionHandler(CatalogoRicetteNonDisponibile.class)
    ResponseEntity<ProblemDetail> ricetteNonDisponibile(CatalogoRicetteNonDisponibile errore) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RIPROVA_TRA_SECONDI)
                .body(problema(HttpStatus.SERVICE_UNAVAILABLE, "Ricette non disponibile", errore.getMessage()));
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
