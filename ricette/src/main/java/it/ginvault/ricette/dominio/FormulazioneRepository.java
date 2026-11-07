package it.ginvault.ricette.dominio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository dell'aggregato: si salva e si carica la Formulazione intera
 * (con le sue prove), mai una prova da sola. L'implementazione la genera
 * Spring Data JPA.
 */
public interface FormulazioneRepository extends JpaRepository<Formulazione, UUID> {

    /** Query derivata dal nome del metodo: ... where stato = ? */
    List<Formulazione> findAllByStato(StatoFormulazione stato);
}
