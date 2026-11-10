package it.ginvault.produzione.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una consegna registrata da Magazzino, come la vede Produzione (dall'evento
 * ConsegnaRegistrata). Le regole le applica PianoDiProduzione.registraConsegna.
 *
 * @param esitoQualita "CONFORME" o "NON_CONFORME": testo, perché l'enum è di Magazzino
 */
public record ConsegnaArrivata(UUID ordineId, UUID lottoId, String botanica, String origine, BigDecimal kgOrdinati,
                               BigDecimal kgArrivati, String esitoQualita, LocalDate consegnataIl) {

    boolean conforme() {
        return "CONFORME".equals(esitoQualita);
    }
}
