package it.ginvault.ricette.applicazione;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import it.ginvault.ricette.dominio.Dose;
import it.ginvault.ricette.dominio.Formulazione;
import it.ginvault.ricette.dominio.FormulazioneRepository;
import it.ginvault.ricette.dominio.StatoFormulazione;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servizio applicativo: un metodo per caso d'uso. Non contiene regole di
 * dominio (stanno nell'aggregato): apre la transazione, carica la
 * Formulazione, le delega l'operazione e restituisce la vista.
 *
 * Le modifiche si salvano da sole al commit (dirty checking di JPA); solo una
 * formulazione nuova va passata a save().
 */
@Service
@Transactional
public class ServizioFormulazioni {

    private final FormulazioneRepository repository;

    public ServizioFormulazioni(FormulazioneRepository repository) {
        this.repository = repository;
    }

    public FormulazioneVista crea(String nome, String botanicaDiStagione, List<Dose> dosi) {
        Formulazione formulazione = Formulazione.nuova(nome, botanicaDiStagione, dosi);
        return FormulazioneVista.di(repository.save(formulazione));
    }

    public FormulazioneVista aggiungiVersione(UUID id, List<Dose> dosi) {
        Formulazione formulazione = carica(id);
        formulazione.nuovaVersione(dosi);
        return FormulazioneVista.di(formulazione);
    }

    public FormulazioneVista scegli(UUID id, int versione) {
        Formulazione formulazione = carica(id);
        formulazione.scegli(versione);
        return FormulazioneVista.di(formulazione);
    }

    public FormulazioneVista usaPerEdizione(UUID id, UUID edizioneId) {
        Formulazione formulazione = carica(id);
        formulazione.usaPerEdizione(edizioneId);
        return FormulazioneVista.di(formulazione);
    }

    @Transactional(readOnly = true)
    public FormulazioneVista trova(UUID id) {
        return FormulazioneVista.di(carica(id));
    }

    /**
     * Elenco per il back-office, dalla formulazione con la prova più recente.
     * Con uno stato (es. SCELTA) solo quelle in quello stato; con null tutte.
     */
    @Transactional(readOnly = true)
    public List<FormulazioneVista> elenco(StatoFormulazione stato) {
        List<Formulazione> formulazioni = stato == null ? repository.findAll() : repository.findAllByStato(stato);
        return formulazioni.stream()
                .map(FormulazioneVista::di)
                .sorted(Comparator.comparing(ServizioFormulazioni::ultimaProva).reversed())
                .toList();
    }

    private static Instant ultimaProva(FormulazioneVista formulazione) {
        return formulazione.prove().stream()
                .map(FormulazioneVista.Prova::registrataIl)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
    }

    private Formulazione carica(UUID id) {
        return repository.findById(id).orElseThrow(() -> new FormulazioneNonTrovata(id));
    }
}
