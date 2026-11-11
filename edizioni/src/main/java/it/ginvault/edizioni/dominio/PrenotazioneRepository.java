package it.ginvault.edizioni.dominio;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repository dell'aggregato Prenotazione: Spring Data genera l'implementazione. */
public interface PrenotazioneRepository extends JpaRepository<Prenotazione, UUID> {

    /** Le prenotazioni di una persona, dalla più recente. */
    List<Prenotazione> findByAcquirenteOrderByPrenotataIlDesc(String acquirente);

    /** Le prenotazioni di una persona per un'edizione: servono per il limite per persona. */
    List<Prenotazione> findByEdizioneIdAndAcquirente(UUID edizioneId, String acquirente);

    /** Una prenotazione, solo se è di questa persona. */
    Optional<Prenotazione> findByIdAndAcquirente(UUID id, String acquirente);

    /**
     * Solo l'id dell'edizione di una prenotazione, senza caricarla. Serve per
     * bloccare prima l'edizione (findPerModifica) e leggere DOPO la
     * prenotazione: caricata prima, resterebbe in memoria com'era prima del
     * blocco, magari già cambiata da chi aveva il blocco.
     */
    @Query("select p.edizioneId from Prenotazione p where p.id = :id")
    Optional<UUID> edizioneDi(UUID id);

    /** Come edizioneDi, ma solo se la prenotazione è di questa persona. */
    @Query("select p.edizioneId from Prenotazione p where p.id = :id and p.acquirente = :acquirente")
    Optional<UUID> edizioneDi(UUID id, String acquirente);

    /**
     * Le prenotazioni ancora in attesa di pagamento con il tempo finito, dalla
     * più vecchia (indice ix_prenotazione_scadenza). Solo gli id: ognuna si
     * annulla poi nella sua transazione, con l'edizione bloccata.
     */
    @Query("""
            select p.id from Prenotazione p
            where p.stato = it.ginvault.edizioni.dominio.StatoPrenotazione.IN_ATTESA_PAGAMENTO
              and p.scadeIl <= :adesso
            order by p.scadeIl""")
    List<UUID> scaduteAl(Instant adesso, Pageable quante);
}
