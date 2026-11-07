package it.ginvault.ricette.dominio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository dell'aggregato: si salva e si carica la Formulazione intera
 * (con le sue prove), mai una prova da sola. L'implementazione la genera
 * Spring Data JPA.
 */
public interface FormulazioneRepository extends JpaRepository<Formulazione, UUID> {
}
