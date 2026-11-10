package it.ginvault.magazzino.api;

import java.util.List;
import java.util.UUID;

import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.applicazione.Viste.FornitoreVista;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fornitori con il riepilogo e lo storico delle consegne (puntuali, ridotte,
 * in ritardo). Dal gateway: /api/magazzino/fornitori/{id}
 */
@RestController
@RequestMapping("/fornitori")
@PreAuthorize("hasAnyRole('RESP_ACQUISTI', 'DISTILLATORE', 'ADMIN')")
public class FornitoriController {

    private final ServizioApprovvigionamento servizio;

    public FornitoriController(ServizioApprovvigionamento servizio) {
        this.servizio = servizio;
    }

    /** Tutti i fornitori, in ordine alfabetico (per il menu del nuovo ordine). */
    @GetMapping
    public List<FornitoreVista> elenco() {
        return servizio.fornitori();
    }

    /** La scheda del fornitore: lo storico dalla consegna più recente. */
    @GetMapping("/{id}")
    public FornitoreVista trova(@PathVariable UUID id) {
        return servizio.fornitore(id);
    }
}
