package it.ginvault.magazzino.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import it.ginvault.magazzino.applicazione.ServizioApprovvigionamento;
import it.ginvault.magazzino.applicazione.Viste.EsitoConsegna;
import it.ginvault.magazzino.applicazione.Viste.OrdineVista;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Ordini di acquisto e consegne. Dal gateway: /api/magazzino/ordini/...
 *
 * Autorizzazione (ruoli dal JWT del gateway):
 *  - ordinare e registrare consegne: solo RESP_ACQUISTI (Elena)
 *  - lettura: anche DISTILLATORE (Marco vede cosa arriva) e ADMIN
 */
@RestController
@RequestMapping("/ordini")
public class OrdiniController {

    private final ServizioApprovvigionamento servizio;

    public OrdiniController(ServizioApprovvigionamento servizio) {
        this.servizio = servizio;
    }

    /** Nuovo ordine APERTO. 201 + Location /ordini/{id}. */
    @PostMapping
    @PreAuthorize("hasRole('RESP_ACQUISTI')")
    public ResponseEntity<OrdineVista> crea(@Valid @RequestBody Richieste.NuovoOrdine richiesta) {
        OrdineVista creato = servizio.creaOrdine(richiesta.fornitoreId(), richiesta.botanica(),
                richiesta.kgOrdinati(), richiesta.consegnaAttesa(), richiesta.edizioneId());
        URI indirizzo = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/ordini/{id}").buildAndExpand(creato.id()).toUri();
        return ResponseEntity.created(indirizzo).body(creato);
    }

    /**
     * La consegna dell'ordine, anche ridotta: chiude l'ordine, crea il lotto,
     * aggiorna lo storico del fornitore e mette ConsegnaRegistrata nell'outbox.
     * 201 + Location del lotto nuovo; 409 se l'ordine è già consegnato; 422 se
     * kg o date non tornano.
     */
    @PostMapping("/{id}/consegne")
    @PreAuthorize("hasRole('RESP_ACQUISTI')")
    public ResponseEntity<EsitoConsegna> registraConsegna(@PathVariable UUID id,
                                                          @Valid @RequestBody Richieste.NuovaConsegna richiesta) {
        EsitoConsegna esito = servizio.registraConsegna(id, richiesta.dati());
        URI indirizzo = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/lotti/{id}").buildAndExpand(esito.lotto().id()).toUri();
        return ResponseEntity.created(indirizzo).body(esito);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('RESP_ACQUISTI', 'DISTILLATORE', 'ADMIN')")
    public OrdineVista trova(@PathVariable UUID id) {
        return servizio.ordine(id);
    }

    /** Tutti gli ordini, dal più recente. */
    @GetMapping
    @PreAuthorize("hasAnyRole('RESP_ACQUISTI', 'DISTILLATORE', 'ADMIN')")
    public List<OrdineVista> elenco() {
        return servizio.ordini();
    }
}
