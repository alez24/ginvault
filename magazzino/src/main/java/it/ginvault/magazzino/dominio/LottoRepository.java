package it.ginvault.magazzino.dominio;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repository dell'aggregato LottoDiBotanica. */
public interface LottoRepository extends JpaRepository<LottoDiBotanica, UUID> {

    /** I lotti di una botanica (nome già normalizzato), dal più recente. */
    List<LottoDiBotanica> findByBotanicaOrderByRicevutoIlDesc(String botanica);

    List<LottoDiBotanica> findAllByOrderByRicevutoIlDesc();
}
