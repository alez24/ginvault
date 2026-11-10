package it.ginvault.produzione.api;

import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.applicazione.ServizioProduzione;
import it.ginvault.produzione.applicazione.Viste.PianoVista;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Piani di produzione (per ora in sola lettura: nascono e cambiano con gli
 * eventi di Edizioni e Magazzino). Dal gateway: /api/produzione/piani
 */
@RestController
@RequestMapping("/piani")
@PreAuthorize("hasAnyRole('DISTILLATORE', 'RESP_ACQUISTI', 'ADMIN')")
public class PianiController {

    private final ServizioProduzione servizio;

    public PianiController(ServizioProduzione servizio) {
        this.servizio = servizio;
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
}
