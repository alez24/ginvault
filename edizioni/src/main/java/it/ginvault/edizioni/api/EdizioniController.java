package it.ginvault.edizioni.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import it.ginvault.edizioni.applicazione.EdizioneVista;
import it.ginvault.edizioni.applicazione.ServizioEdizioni;

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
 * API REST delle edizioni. Dal gateway: /api/edizioni/edizioni/...
 *
 * Autorizzazione (ruoli presi dal JWT del gateway):
 *  - creazione e ricalcolo: solo DISTILLATORE (è Marco che decide l'edizione)
 *  - lettura:   DISTILLATORE e ADMIN
 * Chiunque altro riceve 403; senza JWT valido 401 (SecurityConfig).
 * Il ruolo si controlla PRIMA di chiamare Ricette: chi non è autorizzato non
 * genera traffico verso altri servizi.
 */
@RestController
@RequestMapping("/edizioni")
public class EdizioniController {

    private final ServizioEdizioni servizio;

    public EdizioniController(ServizioEdizioni servizio) {
        this.servizio = servizio;
    }

    /** Nuova edizione da una formulazione SCELTA di Ricette. 201 + Location. */
    @PostMapping
    @PreAuthorize("hasRole('DISTILLATORE')")
    public ResponseEntity<EdizioneVista> crea(@Valid @RequestBody Richieste.NuovaEdizione richiesta) {
        EdizioneVista creata = servizio.crea(richiesta.formulazione().id(), richiesta.formulazione().versione(),
                richiesta.botanicaDisponibileKg(), richiesta.origineBotanica(), richiesta.quote());
        URI indirizzo = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/edizioni/{id}").buildAndExpand(creata.id()).toUri();
        return ResponseEntity.created(indirizzo).body(creata);
    }

    /**
     * Nuova disponibilità di botanica: tiratura e quote ricalcolate.
     * 200 con l'edizione aggiornata; 409 se la riduzione toccherebbe SOCI o le
     * bottiglie già prenotate (con la spiegazione); 422 se i kg non bastano
     * per una bottiglia. Non chiama Ricette.
     */
    @PostMapping("/{id}/ricalcolo")
    @PreAuthorize("hasRole('DISTILLATORE')")
    public EdizioneVista ricalcola(@PathVariable UUID id, @Valid @RequestBody Richieste.Ricalcolo richiesta) {
        return servizio.ricalcola(id, richiesta.botanicaDisponibileKg());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISTILLATORE', 'ADMIN')")
    public EdizioneVista trova(@PathVariable UUID id) {
        return servizio.trova(id);
    }

    /** Elenco delle edizioni, dalla più recente. */
    @GetMapping
    @PreAuthorize("hasAnyRole('DISTILLATORE', 'ADMIN')")
    public List<EdizioneVista> elenco() {
        return servizio.elenco();
    }
}
