package it.ginvault.produzione.api;

import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.applicazione.ServizioIntegrazioni;
import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.applicazione.Viste.EsitoIntegrazioneVista;
import it.ginvault.produzione.applicazione.Viste.LottiDelPianoVista;
import it.ginvault.produzione.applicazione.Viste.PianoVista;
import it.ginvault.produzione.dominio.EsitoIntegrazione;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Piani di produzione. Nascono e cambiano con gli eventi di Edizioni e
 * Magazzino; l'unica modifica a mano è l'integrazione di un lotto, che Marco
 * decide (POST /piani/{id}/integrazioni) e che passa dal magazzino via gRPC.
 * Dal gateway: /api/produzione/piani
 */
@RestController
@RequestMapping("/piani")
@PreAuthorize("hasAnyRole('DISTILLATORE', 'RESP_ACQUISTI', 'ADMIN')")
public class PianiController {

    private final ServizioProduzione servizio;
    private final ServizioIntegrazioni integrazioni;

    public PianiController(ServizioProduzione servizio, ServizioIntegrazioni integrazioni) {
        this.servizio = servizio;
        this.integrazioni = integrazioni;
    }

    /** I piani, dal più recente; con ?edizione= solo quello di un'edizione. */
    @GetMapping
    public List<PianoVista> elenco(@RequestParam(name = "edizione", required = false) UUID edizioneId) {
        return servizio.piani(edizioneId);
    }

    @GetMapping("/{id}")
    public PianoVista trova(@PathVariable UUID id) {
        return servizio.piano(id);
    }

    /**
     * I lotti della botanica del piano in magazzino (Giacenze.Disponibilita),
     * con stessaOrigine: quelli che il piano può accettare.
     * 503 se il magazzino non risponde in 2 secondi.
     */
    @GetMapping("/{id}/lotti")
    public LottiDelPianoVista lotti(@PathVariable UUID id) {
        return integrazioni.lotti(id);
    }

    /**
     * Marco integra un lotto proposto da Elena: {"lottoId": "...", "kg": 1.2}.
     * Solo il distillatore (e l'admin): l'annotazione sul metodo sostituisce
     * quella della classe.
     *
     *   201 integrato (resa ricalcolata), 200 era già integrato (richiesta ripetuta)
     *   409 rifiutato, con il motivo: ORIGINE_DIVERSA ("origine diversa: l'edizione
     *       deve restare fedele alla sua origine"), KG_ECCESSIVI, PIANO_COMPLETO...
     *       o RISERVA_RIFIUTATA dal magazzino
     *   503 il magazzino non risponde in 2 secondi
     */
    @PostMapping("/{id}/integrazioni")
    @PreAuthorize("hasAnyRole('DISTILLATORE', 'ADMIN')")
    public ResponseEntity<EsitoIntegrazioneVista> integra(@PathVariable UUID id,
                                                          @Valid @RequestBody NuovaIntegrazione richiesta) {
        EsitoIntegrazioneVista esito = integrazioni.integra(id, richiesta.lottoId(), richiesta.kg());
        HttpStatus stato = esito.esito() == EsitoIntegrazione.GIA_INTEGRATO ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(stato).body(esito);
    }
}
