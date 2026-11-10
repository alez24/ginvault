package it.ginvault.magazzino.dominio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository dell'aggregato Fornitore. */
public interface FornitoreRepository extends JpaRepository<Fornitore, UUID> {

    List<Fornitore> findAllByOrderByNomeAsc();
}
