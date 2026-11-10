package it.ginvault.produzione.infrastruttura.eventi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Legge i messaggi che arrivano dai topic: la busta (docs/eventi.md) e i
 * payload che interessano a Produzione.
 *
 * "Tolerant reader": i campi in più si ignorano (un produttore può
 * aggiungerne senza rompere niente), quelli che servono devono esserci. Un
 * messaggio sbagliato diventa EventoNonValido, che va nel dead letter topic.
 *
 * I record qui sotto sono la COPIA di Produzione dei contratti: nessuna
 * dipendenza Java dagli altri servizi, conta solo il JSON.
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

    EdizioneCreataV1 edizioneCreata(Busta busta) {
        EdizioneCreataV1 evento = payload(busta, 1, EdizioneCreataV1.class);
        richiesti(busta, "edizioneId", evento.edizioneId(), "formulazione", evento.formulazione(),
                "botanicaDiStagione", evento.botanicaDiStagione(), "grammiPerBottiglia", evento.grammiPerBottiglia(),
                "origineBotanica", evento.origineBotanica(), "kgPrevisti", evento.kgPrevisti(),
                "tiratura", evento.tiratura());
        richiesti(busta, "formulazione.id", evento.formulazione().id(),
                "formulazione.versione", evento.formulazione().versione());
        return evento;
    }

    ConsegnaRegistrataV1 consegnaRegistrata(Busta busta) {
        ConsegnaRegistrataV1 evento = payload(busta, 1, ConsegnaRegistrataV1.class);
        richiesti(busta, "ordineId", evento.ordineId(), "edizioneId", evento.edizioneId(),
                "lottoId", evento.lottoId(), "botanica", evento.botanica(), "origine", evento.origine(),
                "kgOrdinati", evento.kgOrdinati(), "kgArrivati", evento.kgArrivati(),
                "esitoQualita", evento.esitoQualita(), "consegnataIl", evento.consegnataIl());
        return evento;
    }

    private <T> T payload(Busta busta, int versioneLetta, Class<T> classe) {
        if (busta.versione() != versioneLetta) {
            throw new EventoNonValido(busta.tipo() + " versione " + busta.versione() + " non supportata: Produzione"
                    + " legge la versione " + versioneLetta + " (eventId " + busta.eventId() + ")");
        }
        try {
            return json.treeToValue(busta.payload(), classe);
        } catch (JsonProcessingException | IllegalArgumentException errore) {
            throw new EventoNonValido("Payload di " + busta.tipo() + " non valido (eventId " + busta.eventId()
                    + "): " + errore.getMessage());
        }
    }

    /** Coppie nome, valore: i valori null sono i campi mancanti. */
    private static void richiesti(Busta busta, Object... nomiEValori) {
        List<String> mancanti = new ArrayList<>();
        for (int i = 0; i < nomiEValori.length; i += 2) {
            if (nomiEValori[i + 1] == null) {
                mancanti.add((String) nomiEValori[i]);
            }
        }
        if (!mancanti.isEmpty()) {
            throw new EventoNonValido(busta.tipo() + " senza " + String.join(", ", mancanti) + " (eventId "
                    + busta.eventId() + ")");
        }
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

    /** EdizioneCreata v1, da edizioni.eventi (solo i campi che servono al piano). */
    record EdizioneCreataV1(UUID edizioneId, FormulazioneV1 formulazione, String botanicaDiStagione,
                            BigDecimal grammiPerBottiglia, String origineBotanica, BigDecimal kgPrevisti,
                            Integer tiratura) {
    }

    record FormulazioneV1(UUID id, Integer versione) {
    }

    /** ConsegnaRegistrata v1, da magazzino.eventi. */
    record ConsegnaRegistrataV1(UUID ordineId, UUID edizioneId, UUID lottoId, UUID fornitoreId, String botanica,
                                String origine, BigDecimal kgOrdinati, BigDecimal kgArrivati, String esitoQualita,
                                LocalDate consegnataIl) {
    }
}
