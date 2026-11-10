package it.ginvault.magazzino.dominio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository dell'aggregato OrdineDiAcquisto: Spring Data genera l'implementazione. */
public interface OrdineRepository extends JpaRepository<OrdineDiAcquisto, UUID> {

    /** Dal più recente. */
    List<OrdineDiAcquisto> findAllByOrderByCreatoIlDesc();
}
