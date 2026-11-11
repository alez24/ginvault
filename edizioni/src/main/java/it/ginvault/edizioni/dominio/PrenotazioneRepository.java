package it.ginvault.edizioni.dominio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository dell'aggregato Prenotazione: Spring Data genera l'implementazione. */
public interface PrenotazioneRepository extends JpaRepository<Prenotazione, UUID> {

    /** Le prenotazioni di una persona, dalla più recente. */
    List<Prenotazione> findByAcquirenteOrderByPrenotataIlDesc(String acquirente);

    /** Le prenotazioni di una persona per un'edizione: servono per il limite per persona. */
    List<Prenotazione> findByEdizioneIdAndAcquirente(UUID edizioneId, String acquirente);

    /** Una prenotazione, solo se è di questa persona. */
    Optional<Prenotazione> findByIdAndAcquirente(UUID id, String acquirente);
}
