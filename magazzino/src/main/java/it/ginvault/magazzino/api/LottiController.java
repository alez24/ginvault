package it.ginvault.magazzino.api;

import java.util.List;
import java.util.UUID;

import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.applicazione.Viste.LottoVista;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lotti di botanica in magazzino (solo lettura: nascono dalle consegne).
 * Dal gateway: /api/magazzino/lotti?botanica=lavanda
 */
@RestController
@RequestMapping("/lotti")
@PreAuthorize("hasAnyRole('RESP_ACQUISTI', 'DISTILLATORE', 'ADMIN')")
public class LottiController {

    private final ServizioApprovvigionamento servizio;

    public LottiController(ServizioApprovvigionamento servizio) {
        this.servizio = servizio;
    }

    /** I lotti, dal più recente; con ?botanica= solo quelli di una botanica. */
    @GetMapping
    public List<LottoVista> elenco(@RequestParam(required = false) String botanica) {
        return servizio.lotti(botanica);
    }

    @GetMapping("/{id}")
    public LottoVista trova(@PathVariable UUID id) {
        return servizio.lotto(id);
    }
}
