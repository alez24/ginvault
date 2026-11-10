package it.ginvault.magazzino.infrastruttura.datiiniziali;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import it.ginvault.magazzino.dominio.EsitoQualita;
import it.ginvault.magazzino.dominio.Fornitore;
import it.ginvault.magazzino.dominio.FornitoreRepository;
import it.ginvault.magazzino.dominio.LottoDiBotanica;
import it.ginvault.magazzino.dominio.LottoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I dati di partenza della storia di Elena, creati all'avvio se mancano:
 *  - il raccoglitore di lavanda dell'Alta Val Varaita, a cui Elena ordina i 12 kg;
 *  - i campi dell'azienda, con un lotto di lavanda da 2 kg: è il lotto che
 *    Elena propone per coprire la differenza e che Marco rifiuta (origine diversa).
 *
 * Id fissi: a ogni avvio si controlla per id, quindi niente doppioni; anche il
 * front-end e la demo (docs/demo-storia-elena.md) possono citarli.
 * Gli ordini non ci sono: li crea Elena durante la demo.
 *
 * Si spegne con magazzino.dati-iniziali=false (i test di integrazione partono vuoti).
 */
@Component
@ConditionalOnProperty(name = "magazzino.dati-iniziali", havingValue = "true", matchIfMissing = true)
public class DatiIniziali implements ApplicationRunner {

    public static final UUID RACCOGLITORI_VAL_VARAITA = UUID.fromString("f0000000-0000-4000-8000-000000000001");
    public static final UUID CAMPI_GINVAULT = UUID.fromString("f0000000-0000-4000-8000-000000000002");
    public static final UUID LOTTO_LAVANDA_DEI_CAMPI = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    private static final Logger LOG = LoggerFactory.getLogger(DatiIniziali.class);

    private final FornitoreRepository fornitori;
    private final LottoRepository lotti;
    private final Clock orologio;

    public DatiIniziali(FornitoreRepository fornitori, LottoRepository lotti, Clock orologio) {
        this.fornitori = fornitori;
        this.lotti = lotti;
        this.orologio = orologio;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments argomenti) {
        int creati = 0;
        if (!fornitori.existsById(RACCOGLITORI_VAL_VARAITA)) {
            fornitori.save(Fornitore.nuovo(RACCOGLITORI_VAL_VARAITA, "Raccoglitori dell'Alta Val Varaita",
                    "Alta Val Varaita (Alpi Cozie)"));
            creati++;
        }
        if (!fornitori.existsById(CAMPI_GINVAULT)) {
            fornitori.save(Fornitore.nuovo(CAMPI_GINVAULT, "Campi GinVault", "Campi GinVault (Saluzzo, 400 m)"));
            creati++;
        }
        if (!lotti.existsById(LOTTO_LAVANDA_DEI_CAMPI)) {
            lotti.save(LottoDiBotanica.senzaOrdine(LOTTO_LAVANDA_DEI_CAMPI, "lavanda",
                    "Campi GinVault (Saluzzo, 400 m)", new BigDecimal("2.000"), LocalDate.of(2027, 7, 31),
                    EsitoQualita.CONFORME, CAMPI_GINVAULT, LocalDate.of(2026, 7, 15), orologio.instant()));
            creati++;
        }
        if (creati > 0) {
            LOG.info("Dati iniziali della storia di Elena: {} elementi creati (fornitori e lotto dei campi)", creati);
        }
    }
}
