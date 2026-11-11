package it.ginvault.edizioni.dominio;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Repository dell'aggregato Edizione: Spring Data genera l'implementazione. */
public interface EdizioneRepository extends JpaRepository<Edizione, UUID> {

    /**
     * L'edizione con il blocco della sua riga (in PostgreSQL Hibernate scrive
     * SELECT ... FOR NO KEY UPDATE: esclude gli altri blocchi uguali, ma non le
     * righe di quota che puntano all'edizione), fino alla fine della
     * transazione. Due prenotazioni sulla
     * stessa edizione non si sovrappongono: la seconda aspetta che la prima
     * finisca e legge le quote già aggiornate. Così bottiglie libere e limite
     * per persona tornano anche con molte richieste insieme, senza 409 per
     * modifica concorrente. Edizioni diverse non si aspettano.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Edizione e where e.id = :id")
    Optional<Edizione> findPerModifica(UUID id);
}
