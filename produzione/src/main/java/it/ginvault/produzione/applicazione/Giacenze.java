package it.ginvault.produzione.applicazione;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import it.ginvault.produzione.dominio.LottoProposto;

/**
 * Quello che Produzione chiede al Magazzino, in modo sincrono. È una "porta":
 * l'applicazione non sa che sotto c'è gRPC (ClientGiacenze), e nei test si
 * può sostituire.
 *
 * Ogni chiamata ha una deadline (2 secondi) e porta il JWT dell'utente.
 */
public interface Giacenze {

    /**
     * I lotti utilizzabili di una botanica, con i kg ancora liberi.
     *
     * @throws MagazzinoNonDisponibile il magazzino non risponde entro la deadline o non è raggiungibile
     * @throws ErroreMagazzino         il magazzino ha risposto con un errore inatteso
     */
    List<LottoProposto> disponibilita(String botanica);

    /**
     * Riserva dei kg di un lotto per un piano. Idempotente: la stessa richiesta
     * ripetuta restituisce la riserva che c'è già.
     *
     * @throws RiservaNonRiuscita      il magazzino rifiuta (kg insufficienti, lotto non utilizzabile...)
     * @throws MagazzinoNonDisponibile il magazzino non risponde entro la deadline o non è raggiungibile
     * @throws ErroreMagazzino         il magazzino ha risposto con un errore inatteso
     */
    Riserva riservaLotto(UUID lottoId, UUID pianoId, BigDecimal kg);

    /**
     * Annulla la riserva del piano sul lotto: la compensazione quando il piano
     * non si salva dopo una riserva. Idempotente: senza riserva non è un errore.
     *
     * @throws RiservaNonRiuscita      il lotto non esiste, o una modifica concorrente (si può ripetere)
     * @throws MagazzinoNonDisponibile il magazzino non risponde entro la deadline o non è raggiungibile
     * @throws ErroreMagazzino         il magazzino ha risposto con un errore inatteso
     */
    Annullamento annullaRiserva(UUID lottoId, UUID pianoId);

    /**
     * @param kgDisponibili i kg del lotto rimasti liberi dopo la riserva
     * @param giaRiservato  true: la riserva c'era già (richiesta ripetuta)
     */
    record Riserva(UUID lottoId, UUID pianoId, String origine, BigDecimal kgRiservati, BigDecimal kgDisponibili,
                   boolean giaRiservato) {
    }

    /**
     * @param kgLiberati     i kg tornati liberi in magazzino (0 se non c'era la riserva)
     * @param nessunaRiserva true: il piano non aveva riserve su quel lotto
     */
    record Annullamento(BigDecimal kgLiberati, boolean nessunaRiserva) {
    }
}
