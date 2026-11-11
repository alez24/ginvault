package it.ginvault.edizioni.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import it.ginvault.edizioni.applicazione.PrenotazioneVista;
import it.ginvault.edizioni.applicazione.RegoleDiVenditaVista;
import it.ginvault.edizioni.applicazione.ServizioPrenotazioni;
import it.ginvault.edizioni.dominio.Acquirente;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * API REST delle prenotazioni (storia di Giulia). Dal gateway:
 *   POST /api/edizioni/edizioni/{id}/prenotazioni   prenota
 *   GET  /api/edizioni/prenotazioni                 le mie prenotazioni
 *   GET  /api/edizioni/prenotazioni/{id}            una mia prenotazione
 *   POST /api/edizioni/prenotazioni/{id}/pagamento  pago con una carta di prova
 *   GET  /api/edizioni/regole-di-vendita            prelazione, limiti, prezzi, carte
 *
 * Chi prenota è l'utente del JWT: il "sub" diventa l'acquirente e il ruolo
 * SOCIO decide se può prenotare in prelazione. Ognuno vede solo le sue
 * prenotazioni.
 *
 * Autorizzazione: prenotazioni per SOCIO e CLIENTE (in prelazione il dominio
 * accetta solo i soci, con un 409 che spiega il perché); le regole di vendita
 * per tutti i ruoli. Senza JWT valido 401, ruolo sbagliato 403.
 */
@RestController
public class PrenotazioniController {

    private final ServizioPrenotazioni servizio;

    public PrenotazioniController(ServizioPrenotazioni servizio) {
        this.servizio = servizio;
    }

    /**
     * Prenota bottiglie di un'edizione. 201 + Location con la prenotazione, in
     * attesa di pagamento. 409 se è in prelazione e non sei socio, se superi il
     * limite per persona (motivo nel corpo) o se la quota non ha abbastanza
     * bottiglie libere; 404 se l'edizione non c'è.
     */
    @PostMapping("/edizioni/{id}/prenotazioni")
    @PreAuthorize("hasAnyRole('SOCIO', 'CLIENTE')")
    public ResponseEntity<PrenotazioneVista> prenota(@PathVariable UUID id,
                                                     @Valid @RequestBody Richieste.NuovaPrenotazione richiesta,
                                                     JwtAuthenticationToken utente) {
        PrenotazioneVista prenotazione = servizio.prenota(id, acquirente(utente), richiesta.bottiglie());
        URI indirizzo = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/prenotazioni/{id}").buildAndExpand(prenotazione.id()).toUri();
        return ResponseEntity.created(indirizzo).body(prenotazione);
    }

    /** Le prenotazioni di chi chiama, dalla più recente. */
    @GetMapping("/prenotazioni")
    @PreAuthorize("hasAnyRole('SOCIO', 'CLIENTE')")
    public List<PrenotazioneVista> mie(JwtAuthenticationToken utente) {
        return servizio.sue(utente.getToken().getSubject());
    }

    /** Una prenotazione di chi chiama; 404 anche se è di un altro. */
    @GetMapping("/prenotazioni/{id}")
    @PreAuthorize("hasAnyRole('SOCIO', 'CLIENTE')")
    public PrenotazioneVista mia(@PathVariable UUID id, JwtAuthenticationToken utente) {
        return servizio.trova(id, utente.getToken().getSubject());
    }

    /**
     * Chiede di pagare una mia prenotazione con una carta di prova del menu.
     * 202 Accepted: il pagamento è partito ma l'esito arriva dopo, da
     * Pagamenti (la SAGA). Il front-end rilegge la prenotazione finché non è
     * CONFERMATA (con i numeri di bottiglia) o ANNULLATA. 400 se la carta non
     * è nel menu, 404 se non è mia, 409 se il pagamento è già chiesto, il
     * tempo è finito o la prenotazione è chiusa (motivo nel corpo).
     */
    @PostMapping("/prenotazioni/{id}/pagamento")
    @PreAuthorize("hasAnyRole('SOCIO', 'CLIENTE')")
    public ResponseEntity<PrenotazioneVista> paga(@PathVariable UUID id,
                                                  @Valid @RequestBody Richieste.Pagamento richiesta,
                                                  JwtAuthenticationToken utente) {
        PrenotazioneVista prenotazione = servizio.paga(id, utente.getToken().getSubject(), richiesta.metodo());
        return ResponseEntity.accepted().body(prenotazione);
    }

    /** Durata della prelazione, bottiglie per persona, prezzi, tempo per pagare e carte di prova. */
    @GetMapping("/regole-di-vendita")
    @PreAuthorize("hasAnyRole('SOCIO', 'CLIENTE', 'DISTILLATORE', 'RESP_ACQUISTI', 'ADMIN')")
    public RegoleDiVenditaVista regole() {
        return servizio.regole();
    }

    private static Acquirente acquirente(JwtAuthenticationToken utente) {
        boolean socio = utente.getAuthorities().stream()
                .anyMatch(autorita -> "ROLE_SOCIO".equals(autorita.getAuthority()));
        return new Acquirente(utente.getToken().getSubject(), socio);
    }
}
