package it.ginvault.produzione.infrastruttura.eventi;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventiElaboratiRepository extends JpaRepository<EventoElaborato, UUID> {

    long countByTopic(String topic);
}
