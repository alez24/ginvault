package it.ginvault.ricette.api;

import java.net.URI;
import java.util.UUID;

import it.ginvault.ricette.applicazione.FormulazioneVista;
import it.ginvault.ricette.applicazione.ServizioFormulazioni;

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
 * API REST delle formulazioni. Dal gateway: /api/ricette/formulazioni/...
 *
 * Autorizzazione (ruoli presi dal JWT del gateway):
 *  - scrittura: solo DISTILLATORE (è Marco che prepara le prove)
 *  - lettura:   DISTILLATORE e ADMIN
 * Chiunque altro riceve 403; senza JWT valido 401 (SecurityConfig).
 *
 * Nessuna PUT/PATCH: una versione non si modifica, se ne crea una nuova.
 */
@RestController
@RequestMapping("/formulazioni")
public class FormulazioniController {

    private final ServizioFormulazioni servizio;

    public FormulazioniController(ServizioFormulazioni servizio) {
        this.servizio = servizio;
    }

    /** Nuova formulazione IN_PROVA con la prima prova (versione 1). 201 + Location. */
    @PostMapping
    @PreAuthorize("hasRole('DISTILLATORE')")
    public ResponseEntity<FormulazioneVista> crea(@Valid @RequestBody Richieste.NuovaFormulazione richiesta) {
        FormulazioneVista creata = servizio.crea(richiesta.nome(), richiesta.botanicaDiStagione(),
                Richieste.comeDosi(richiesta.dosi()));
        return ResponseEntity.created(indirizzoDi(creata.id())).body(creata);
    }

    /** Nuova prova = versione successiva; le precedenti restano intatte. 201 + Location della formulazione. */
    @PostMapping("/{id}/versioni")
    @PreAuthorize("hasRole('DISTILLATORE')")
    public ResponseEntity<FormulazioneVista> nuovaVersione(@PathVariable UUID id,
                                                           @Valid @RequestBody Richieste.NuovaVersione richiesta) {
        FormulazioneVista aggiornata = servizio.aggiungiVersione(id, Richieste.comeDosi(richiesta.dosi()));
        return ResponseEntity.created(indirizzoDi(id)).body(aggiornata);
    }

    /** Il panel sceglie una versione: la formulazione diventa SCELTA. */
    @PostMapping("/{id}/scelta")
    @PreAuthorize("hasRole('DISTILLATORE')")
    public FormulazioneVista scegli(@PathVariable UUID id, @Valid @RequestBody Richieste.Scelta richiesta) {
        return servizio.scegli(id, richiesta.versione());
    }

    /**
     * La formulazione viene usata per un'edizione: diventa USATA e non si riusa più.
     * La chiama Edizioni quando crea l'edizione, con il JWT dell'utente (DISTILLATORE).
     */
    @PostMapping("/{id}/utilizzo")
    @PreAuthorize("hasRole('DISTILLATORE')")
    public FormulazioneVista usa(@PathVariable UUID id, @Valid @RequestBody Richieste.Utilizzo richiesta) {
        return servizio.usaPerEdizione(id, richiesta.edizioneId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISTILLATORE', 'ADMIN')")
    public FormulazioneVista trova(@PathVariable UUID id) {
        return servizio.trova(id);
    }

    /** /formulazioni/{id}, costruito dall'indirizzo della richiesta corrente. */
    private static URI indirizzoDi(UUID id) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/formulazioni/{id}").buildAndExpand(id).toUri();
    }
}
