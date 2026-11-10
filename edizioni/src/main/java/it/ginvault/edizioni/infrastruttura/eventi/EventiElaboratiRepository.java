package it.ginvault.edizioni.infrastruttura.eventi;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventiElaboratiRepository extends JpaRepository<EventoElaborato, UUID> {
}
