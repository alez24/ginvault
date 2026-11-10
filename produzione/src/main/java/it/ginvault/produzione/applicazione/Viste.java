package it.ginvault.produzione.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.dominio.ConsegnaNelPiano;
import it.ginvault.produzione.dominio.EsitoIntegrazione;
import it.ginvault.produzione.dominio.IntegrazioneNelPiano;
import it.ginvault.produzione.dominio.LottoProposto;
import it.ginvault.produzione.dominio.Origini;
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
     * kgPrevisti 12, kgDisponibili 10.8, kgMancanti 1.2, tiraturaPrevista 600,
     * bottiglieProducibili 540.
     */
    public record PianoVista(UUID id, UUID edizioneId, FormulazioneVista formulazione, String botanica,
                             BigDecimal grammiPerBottiglia, String origineRichiesta, BigDecimal kgPrevisti,
                             BigDecimal kgDisponibili, BigDecimal kgMancanti, int tiraturaPrevista,
                             int bottiglieProducibili, List<ConsegnaVista> consegne,
                             List<IntegrazioneVista> integrazioni, Instant creatoIl, Instant aggiornatoIl) {

        static PianoVista di(PianoDiProduzione piano) {
            return new PianoVista(piano.id(), piano.edizioneId(),
                    new FormulazioneVista(piano.formulazioneId(), piano.formulazioneVersione()), piano.botanica(),
                    piano.grammiPerBottiglia(), piano.origineRichiesta(), piano.kgPrevisti(), piano.kgDisponibili(),
                    piano.kgMancanti(), piano.tiraturaPrevista(), piano.bottiglieProducibili(),
                    piano.consegne().stream().map(ConsegnaVista::di).toList(),
                    piano.integrazioni().stream().map(IntegrazioneVista::di).toList(), piano.creatoIl(),
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

    /** Un lotto del magazzino integrato nel piano, con i kg riservati. */
    public record IntegrazioneVista(UUID lottoId, String origine, BigDecimal kg, String motivo, Instant integrataIl) {

        static IntegrazioneVista di(IntegrazioneNelPiano integrazione) {
            return new IntegrazioneVista(integrazione.lottoId(), integrazione.origine(), integrazione.kg(),
                    integrazione.motivo(), integrazione.integrataIl());
        }
    }

    /** Il risultato di POST /piani/{id}/integrazioni: cosa è successo e il piano aggiornato. */
    public record EsitoIntegrazioneVista(EsitoIntegrazione esito, PianoVista piano) {
    }

    /**
     * GET /piani/{id}/lotti: i lotti utilizzabili della botanica del piano in
     * magazzino. stessaOrigine dice in anticipo quali il piano può accettare.
     */
    public record LottiDelPianoVista(UUID pianoId, String botanica, String origineRichiesta, BigDecimal kgMancanti,
                                     List<LottoDelPianoVista> lotti) {

        static LottiDelPianoVista di(PianoVista piano, List<LottoProposto> lotti) {
            return new LottiDelPianoVista(piano.id(), piano.botanica(), piano.origineRichiesta(), piano.kgMancanti(),
                    lotti.stream().map(lotto -> LottoDelPianoVista.di(lotto, piano.origineRichiesta())).toList());
        }
    }

    public record LottoDelPianoVista(UUID lottoId, String origine, BigDecimal kg, BigDecimal kgDisponibili,
                                     LocalDate scadenza, String fornitore, boolean stessaOrigine) {

        static LottoDelPianoVista di(LottoProposto lotto, String origineRichiesta) {
            return new LottoDelPianoVista(lotto.lottoId(), lotto.origine(), lotto.kg(), lotto.kgDisponibili(),
                    lotto.scadenza(), lotto.fornitore(), Origini.stessa(lotto.origine(), origineRichiesta));
        }
    }
}
