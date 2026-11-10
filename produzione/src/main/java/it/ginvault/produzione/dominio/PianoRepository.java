package it.ginvault.produzione.dominio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PianoRepository extends JpaRepository<PianoDiProduzione, UUID> {

    Optional<PianoDiProduzione> findByEdizioneId(UUID edizioneId);

    boolean existsByEdizioneId(UUID edizioneId);

    List<PianoDiProduzione> findAllByOrderByCreatoIlDesc();
}
