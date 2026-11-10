package it.ginvault.edizioni.dominio;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Quello che a Edizioni serve dell'evento ResaRicalcolata di Produzione: i kg
 * di botanica di stagione su cui il piano può contare adesso. Il consumer
 * traduce il messaggio in questo valore (anti-corruption layer): il dominio
 * non conosce il JSON né Kafka.
 *
 * @param eventoId      l'eventId della ResaRicalcolata: torna negli eventi
 *                      di Edizioni, per ritrovare il messaggio che li ha causati
 * @param pianoId       il piano di produzione dell'edizione
 * @param kgDisponibili i kg su cui contare adesso (es. 10,8)
 * @param motivo        in italiano, da Produzione: "consegna ridotta: 10,8 su 12 kg"
 */
public record ResaDiProduzione(UUID eventoId, UUID pianoId, BigDecimal kgDisponibili, String motivo) {
}
