package it.ginvault.pagamenti.dominio;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PagamentoRepository extends JpaRepository<Pagamento, UUID> {

    /** Al più uno: prenotazione_id è unico (un pagamento per prenotazione). */
    Optional<Pagamento> findByPrenotazioneId(UUID prenotazioneId);
}
