package it.ginvault.edizioni.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.springframework.stereotype.Component;

/**
 * Legge i messaggi di produzione.eventi: la busta (docs/eventi.md) e il
 * payload di ResaRicalcolata. Come in Produzione:
 *
 * "Tolerant reader": i campi in più si ignorano (Produzione può aggiungerne
 * senza rompere niente, come lottoId nel passo 4), quelli che servono devono
 * esserci. Un messaggio sbagliato diventa EventoNonValido, che va nel dead
 * letter topic.
 *
 * Il record qui sotto è la COPIA di Edizioni del contratto: nessuna
 * dipendenza Java da Produzione, conta solo il JSON.
 */
@Component
class LettoreEventi {

    /** Numeri decimali come BigDecimal, non double: 10.8 resta 10.8 e non 10.800000000000001. */
    private final ObjectReader lettore;
    private final ObjectMapper json;

    LettoreEventi(ObjectMapper json) {
        this.json = json;
        this.lettore = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    Busta busta(String messaggio) {
        if (messaggio == null || messaggio.isBlank()) {
            throw new EventoNonValido("Messaggio vuoto");
        }
        JsonNode radice;
        try {
            radice = lettore.readTree(messaggio);
        } catch (JsonProcessingException errore) {
            throw new EventoNonValido("Il messaggio non è JSON: " + errore.getOriginalMessage());
        }
        if (radice == null || !radice.isObject()) {
            throw new EventoNonValido("Il messaggio non è un oggetto JSON");
        }
        UUID eventId = uuid(radice, "eventId");
        JsonNode tipo = radice.get("tipo");
        if (tipo == null || !tipo.isTextual() || tipo.asText().isBlank()) {
            throw new EventoNonValido("Busta senza tipo (eventId " + eventId + ")");
        }
        JsonNode versione = radice.get("versione");
        if (versione == null || !versione.canConvertToExactIntegral() || !versione.canConvertToInt()) {
            throw new EventoNonValido("Busta senza versione (eventId " + eventId + ")");
        }
        JsonNode payload = radice.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new EventoNonValido("Busta senza payload (eventId " + eventId + ")");
        }
        return new Busta(eventId, tipo.asText(), versione.intValue(), istante(radice, "occorsoIl"), payload);
    }

    /** ResaRicalcolata v1: servono edizione, piano e kg disponibili (al grammo, mai negativi). */
    ResaRicalcolataV1 resaRicalcolata(Busta busta) {
        if (busta.versione() != 1) {
            throw new EventoNonValido(busta.tipo() + " versione " + busta.versione() + " non supportata: Edizioni"
                    + " legge la versione 1 (eventId " + busta.eventId() + ")");
        }
        ResaRicalcolataV1 evento;
        try {
            evento = lettore.treeToValue(busta.payload(), ResaRicalcolataV1.class);
        } catch (JsonProcessingException | IllegalArgumentException errore) {
            throw new EventoNonValido("Payload di " + busta.tipo() + " non valido (eventId " + busta.eventId()
                    + "): " + errore.getMessage());
        }
        List<String> mancanti = new ArrayList<>();
        if (evento.pianoId() == null) {
            mancanti.add("pianoId");
        }
        if (evento.edizioneId() == null) {
            mancanti.add("edizioneId");
        }
        if (evento.kgDisponibili() == null) {
            mancanti.add("kgDisponibili");
        }
        if (!mancanti.isEmpty()) {
            throw new EventoNonValido(busta.tipo() + " senza " + String.join(", ", mancanti) + " (eventId "
                    + busta.eventId() + ")");
        }
        BigDecimal kg = evento.kgDisponibili();
        if (kg.signum() < 0 || kg.stripTrailingZeros().scale() > 3) {
            throw new EventoNonValido(busta.tipo() + " con kgDisponibili non validi: " + kg.toPlainString()
                    + " (eventId " + busta.eventId() + ")");
        }
        return evento;
    }

    private static UUID uuid(JsonNode radice, String campo) {
        JsonNode valore = radice.get(campo);
        try {
            if (valore != null && valore.isTextual()) {
                return UUID.fromString(valore.asText());
            }
        } catch (IllegalArgumentException errore) {
            // sotto: stesso messaggio
        }
        throw new EventoNonValido("Busta senza " + campo + " valido");
    }

    private static Instant istante(JsonNode radice, String campo) {
        JsonNode valore = radice.get(campo);
        try {
            if (valore != null && valore.isTextual()) {
                return Instant.parse(valore.asText());
            }
        } catch (DateTimeParseException errore) {
            // sotto: stesso messaggio
        }
        throw new EventoNonValido("Busta senza " + campo + " valido");
    }

    /** {eventId, tipo, versione, occorsoIl, payload}: uguale per tutti gli eventi. */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, JsonNode payload) {
    }

    /** ResaRicalcolata v1, da produzione.eventi (solo i campi che servono a Edizioni). */
    record ResaRicalcolataV1(UUID pianoId, UUID edizioneId, String botanica, BigDecimal kgPrevisti,
                             BigDecimal kgDisponibiliPrima, BigDecimal kgDisponibili, String motivo) {
    }
}
