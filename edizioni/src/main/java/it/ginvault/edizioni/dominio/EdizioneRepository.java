package it.ginvault.edizioni.dominio;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository dell'aggregato Edizione: Spring Data genera l'implementazione. */
public interface EdizioneRepository extends JpaRepository<Edizione, UUID> {
}
