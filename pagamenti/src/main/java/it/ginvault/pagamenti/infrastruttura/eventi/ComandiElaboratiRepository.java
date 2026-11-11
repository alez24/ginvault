package it.ginvault.pagamenti.infrastruttura.eventi;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComandiElaboratiRepository extends JpaRepository<ComandoElaborato, UUID> {
}
