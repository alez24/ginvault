package it.ginvault.edizioni.infrastruttura.eventi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<RigaOutbox, Long> {

    /** Gli eventi non ancora pubblicati, nell'ordine in cui sono stati scritti. */
    @Query(value = "select * from outbox where pubblicato_il is null order by numero limit :quanti",
            nativeQuery = true)
    List<RigaOutbox> daPubblicare(@Param("quanti") int quanti);

    /**
     * Blocco "advisory" di PostgreSQL, valido fino alla fine della transazione:
     * true per una sola transazione alla volta in tutto il database. Con più
     * repliche di edizioni, un solo publisher lavora a ogni giro, e gli eventi
     * della stessa edizione non si superano.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:chiave)", nativeQuery = true)
    boolean bloccaPublisher(@Param("chiave") long chiave);

    Optional<RigaOutbox> findByEventId(UUID eventId);

    long countByPubblicatoIlIsNull();
}
