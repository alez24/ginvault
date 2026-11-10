package it.ginvault.magazzino.applicazione;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import it.ginvault.magazzino.dominio.Consegna;
import it.ginvault.magazzino.dominio.EsitoQualita;
import it.ginvault.magazzino.dominio.EsitoRiserva;
import it.ginvault.magazzino.dominio.Fornitore;
import it.ginvault.magazzino.dominio.LottoDiBotanica;
import it.ginvault.magazzino.dominio.OrdineDiAcquisto;
import it.ginvault.magazzino.dominio.RiservaDiLotto;
import it.ginvault.magazzino.dominio.StatoOrdine;
import it.ginvault.magazzino.dominio.VoceStorico;

/**
 * Quello che l'API restituisce (JSON). Si costruisce dagli aggregati, che non
 * escono mai dal servizio. I valori calcolati (ridotta, inRitardo, kgMancanti)
 * li calcola il dominio: il front-end li mostra e basta.
 */
public final class Viste {

    private Viste() {
    }

    /** Il fornitore citato da un ordine o da un lotto: id e nome. */
    public record RiferimentoFornitore(UUID id, String nome) {
    }

    /**
     * Un ordine di acquisto:
     * {"id", "fornitore": {"id", "nome"}, "botanica", "kgOrdinati", "consegnaAttesa",
     *  "edizioneId", "stato", "consegna": null | {...}, "creatoIl"}
     */
    public record OrdineVista(UUID id, RiferimentoFornitore fornitore, String botanica, BigDecimal kgOrdinati,
                              LocalDate consegnaAttesa, UUID edizioneId, StatoOrdine stato, ConsegnaVista consegna,
                              Instant creatoIl) {

        static OrdineVista di(OrdineDiAcquisto ordine, RiferimentoFornitore fornitore) {
            return new OrdineVista(ordine.id(), fornitore, ordine.botanica(), ordine.kgOrdinati(),
                    ordine.consegnaAttesa(), ordine.edizioneId(), ordine.stato(), ConsegnaVista.di(ordine),
                    ordine.creatoIl());
        }
    }

    /**
     * La consegna di un ordine. Per la consegna di Elena: kgArrivati 10.8,
     * kgMancanti 1.2, ridotta true, inRitardo true, giorniDiRitardo 6.
     */
    public record ConsegnaVista(LocalDate consegnataIl, BigDecimal kgArrivati, BigDecimal kgMancanti,
                                boolean ridotta, boolean inRitardo, long giorniDiRitardo, String origine,
                                UUID lottoId, String nota) {

        static ConsegnaVista di(OrdineDiAcquisto ordine) {
            Consegna consegna = ordine.consegna();
            if (consegna == null) {
                return null;
            }
            return new ConsegnaVista(consegna.consegnataIl(), consegna.kgArrivati(), ordine.kgMancanti(),
                    ordine.ridotta(), ordine.inRitardo(), ordine.giorniDiRitardo(), consegna.origine(),
                    consegna.lottoId(), consegna.nota());
        }
    }

    /** Un lotto di botanica, con la tracciabilità. ordineId è null per i lotti senza ordine. */
    public record LottoVista(UUID id, String botanica, String origine, BigDecimal kg, BigDecimal kgDisponibili,
                             LocalDate scadenza, EsitoQualita esitoQualita, boolean utilizzabile,
                             RiferimentoFornitore fornitore, UUID ordineId, LocalDate ricevutoIl) {

        static LottoVista di(LottoDiBotanica lotto, RiferimentoFornitore fornitore, LocalDate oggi) {
            return new LottoVista(lotto.id(), lotto.botanica(), lotto.origine(), lotto.kg(), lotto.kgDisponibili(),
                    lotto.scadenza(), lotto.esitoQualita(), lotto.utilizzabile(oggi), fornitore, lotto.ordineId(),
                    lotto.ricevutoIl());
        }
    }

    /**
     * Risposta di Giacenze.Disponibilita (gRPC): i lotti utilizzabili di una
     * botanica, con i kg liberi da riserve.
     */
    public record DisponibilitaVista(String botanica, List<LottoDisponibileVista> lotti,
                                     BigDecimal kgDisponibiliTotali) {
    }

    public record LottoDisponibileVista(UUID id, String botanica, String origine, BigDecimal kg,
                                        BigDecimal kgDisponibili, LocalDate scadenza,
                                        RiferimentoFornitore fornitore) {

        static LottoDisponibileVista di(LottoDiBotanica lotto, RiferimentoFornitore fornitore) {
            return new LottoDisponibileVista(lotto.id(), lotto.botanica(), lotto.origine(), lotto.kg(),
                    lotto.kgDisponibili(), lotto.scadenza(), fornitore);
        }
    }

    /** Risposta di Giacenze.RiservaLotto (gRPC). */
    public record RiservaVista(UUID lottoId, UUID pianoId, String botanica, String origine, BigDecimal kgRiservati,
                               BigDecimal kgDisponibili, boolean giaRiservato) {

        static RiservaVista di(LottoDiBotanica lotto, UUID pianoId, EsitoRiserva esito) {
            return new RiservaVista(lotto.id(), pianoId, lotto.botanica(), lotto.origine(), esito.kgRiservati(),
                    esito.kgDisponibili(), esito.giaRiservato());
        }
    }

    /**
     * Il risultato di Giacenze.AnnullaRiserva.
     *
     * @param kgLiberati     i kg tornati liberi (0 se non c'era nessuna riserva)
     * @param nessunaRiserva true: il piano non aveva riserve su questo lotto
     */
    public record AnnullamentoVista(UUID lottoId, UUID pianoId, BigDecimal kgLiberati, BigDecimal kgDisponibili,
                                    boolean nessunaRiserva) {

        static AnnullamentoVista di(LottoDiBotanica lotto, UUID pianoId, Optional<RiservaDiLotto> annullata) {
            BigDecimal liberati = annullata.map(RiservaDiLotto::kg).orElse(BigDecimal.ZERO.setScale(3));
            return new AnnullamentoVista(lotto.id(), pianoId, liberati, lotto.kgDisponibili(), annullata.isEmpty());
        }
    }

    /** Il risultato di POST /ordini/{id}/consegne: l'ordine chiuso e il lotto nuovo. */
    public record EsitoConsegna(OrdineVista ordine, LottoVista lotto) {
    }

    /** Un fornitore con il riepilogo e lo storico, dalla consegna più recente. */
    public record FornitoreVista(UUID id, String nome, String origine, Fornitore.Riepilogo riepilogo,
                                 List<VoceStoricoVista> storico) {

        static FornitoreVista di(Fornitore fornitore) {
            List<VoceStoricoVista> voci = fornitore.storico().stream().map(VoceStoricoVista::di).toList().reversed();
            return new FornitoreVista(fornitore.id(), fornitore.nome(), fornitore.origine(), fornitore.riepilogo(),
                    voci);
        }
    }

    public record VoceStoricoVista(UUID ordineId, String botanica, BigDecimal kgOrdinati, BigDecimal kgArrivati,
                                   LocalDate consegnaAttesa, LocalDate consegnataIl, boolean ridotta,
                                   boolean inRitardo, long giorniDiRitardo, String nota) {

        static VoceStoricoVista di(VoceStorico voce) {
            return new VoceStoricoVista(voce.ordineId(), voce.botanica(), voce.kgOrdinati(), voce.kgArrivati(),
                    voce.consegnaAttesa(), voce.consegnataIl(), voce.ridotta(), voce.inRitardo(),
                    voce.giorniDiRitardo(), voce.nota());
        }
    }
}
