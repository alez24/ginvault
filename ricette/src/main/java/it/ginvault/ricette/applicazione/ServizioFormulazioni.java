package it.ginvault.ricette.applicazione;

import java.util.List;
import java.util.UUID;

import it.ginvault.ricette.dominio.Dose;
import it.ginvault.ricette.dominio.Formulazione;
import it.ginvault.ricette.dominio.FormulazioneRepository;

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

    private Formulazione carica(UUID id) {
        return repository.findById(id).orElseThrow(() -> new FormulazioneNonTrovata(id));
    }
}
