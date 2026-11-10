package it.ginvault.produzione.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.dominio.ConsegnaNelPiano;
import it.ginvault.produzione.dominio.PianoDiProduzione;

/**
 * Quello che l'API restituisce (JSON). Si costruisce dagli aggregati, che non
 * escono mai dal servizio.
 */
public final class Viste {

    private Viste() {
    }

    /**
     * Un piano di produzione. Per l'edizione d'estate, dopo la consegna di Elena:
     * kgPrevisti 12, kgDisponibili 10.8, tiraturaPrevista 600, bottiglieProducibili 540.
     */
    public record PianoVista(UUID id, UUID edizioneId, FormulazioneVista formulazione, String botanica,
                             BigDecimal grammiPerBottiglia, String origineRichiesta, BigDecimal kgPrevisti,
                             BigDecimal kgDisponibili, int tiraturaPrevista, int bottiglieProducibili,
                             List<ConsegnaVista> consegne, Instant creatoIl, Instant aggiornatoIl) {

        static PianoVista di(PianoDiProduzione piano) {
            return new PianoVista(piano.id(), piano.edizioneId(),
                    new FormulazioneVista(piano.formulazioneId(), piano.formulazioneVersione()), piano.botanica(),
                    piano.grammiPerBottiglia(), piano.origineRichiesta(), piano.kgPrevisti(), piano.kgDisponibili(),
                    piano.tiraturaPrevista(), piano.bottiglieProducibili(),
                    piano.consegne().stream().map(ConsegnaVista::di).toList(), piano.creatoIl(),
                    piano.aggiornatoIl());
        }
    }

    public record FormulazioneVista(UUID id, int versione) {
    }

    /** Una consegna applicata al piano, con i kg utilizzabili e il motivo. */
    public record ConsegnaVista(UUID ordineId, UUID lottoId, String origine, BigDecimal kgOrdinati,
                                BigDecimal kgArrivati, BigDecimal kgUtilizzabili, String motivo,
                                LocalDate consegnataIl) {

        static ConsegnaVista di(ConsegnaNelPiano consegna) {
            return new ConsegnaVista(consegna.ordineId(), consegna.lottoId(), consegna.origine(),
                    consegna.kgOrdinati(), consegna.kgArrivati(), consegna.kgUtilizzabili(), consegna.motivo(),
                    consegna.consegnataIl());
        }
    }
}
