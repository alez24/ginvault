package it.ginvault.pagamenti.infrastruttura.eventi;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.ginvault.pagamenti.dominio.RichiestaDiPagamento;

import org.springframework.stereotype.Component;

/**
 * Legge i comandi che arrivano da pagamenti.comandi: la busta (docs/eventi.md,
 * la stessa degli eventi: eventId è l'id del messaggio) e i payload.
 *
 * "Tolerant reader", come negli altri servizi: i campi in più si ignorano,
 * quelli che servono devono esserci. Un comando sbagliato diventa
 * ComandoNonValido, che va nel dead letter topic.
 *
 * I record qui sotto sono la COPIA di Pagamenti dei contratti: nessuna
 * dipendenza Java da Edizioni, conta solo il JSON.
 */
@Component
class LettoreComandi {

    private final ObjectMapper json;

    LettoreComandi(ObjectMapper json) {
        // Un importo con i decimali (9000.5 centesimi) è un errore, non si tronca
        this.json = json.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    Busta busta(String messaggio) {
        if (messaggio == null || messaggio.isBlank()) {
            throw new ComandoNonValido("Messaggio vuoto");
        }
        JsonNode radice;
        try {
            radice = json.readTree(messaggio);
        } catch (JsonProcessingException errore) {
            throw new ComandoNonValido("Il messaggio non è JSON: " + errore.getOriginalMessage());
        }
        if (radice == null || !radice.isObject()) {
            throw new ComandoNonValido("Il messaggio non è un oggetto JSON");
        }
        UUID eventId = uuid(radice, "eventId");
        JsonNode tipo = radice.get("tipo");
        if (tipo == null || !tipo.isTextual() || tipo.asText().isBlank()) {
            throw new ComandoNonValido("Busta senza tipo (eventId " + eventId + ")");
        }
        JsonNode versione = radice.get("versione");
        if (versione == null || !versione.canConvertToExactIntegral() || !versione.canConvertToInt()) {
            throw new ComandoNonValido("Busta senza versione (eventId " + eventId + ")");
        }
        JsonNode payload = radice.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new ComandoNonValido("Busta senza payload (eventId " + eventId + ")");
        }
        return new Busta(eventId, tipo.asText(), versione.intValue(), istante(radice, "occorsoIl"), payload);
    }

    RichiestaDiPagamento richiestaPagamento(Busta busta) {
        RichiestaPagamentoV1 comando = payload(busta, 1, RichiestaPagamentoV1.class);
        richiesti(busta, "prenotazioneId", comando.prenotazioneId(), "edizioneId", comando.edizioneId(),
                "acquirente", comando.acquirente(), "importoCentesimi", comando.importoCentesimi(),
                "valuta", comando.valuta(), "metodo", comando.metodo());
        try {
            return new RichiestaDiPagamento(comando.prenotazioneId(), comando.edizioneId(), comando.acquirente(),
                    comando.importoCentesimi(), comando.valuta(), comando.metodo(), comando.descrizione());
        } catch (IllegalArgumentException errore) {
            throw new ComandoNonValido("RichiestaPagamento non valida (eventId " + busta.eventId() + "): "
                    + errore.getMessage());
        }
    }

    RichiestaRimborsoV1 richiestaRimborso(Busta busta) {
        RichiestaRimborsoV1 comando = payload(busta, 1, RichiestaRimborsoV1.class);
        richiesti(busta, "prenotazioneId", comando.prenotazioneId());
        return comando;
    }

    private <T> T payload(Busta busta, int versioneLetta, Class<T> classe) {
        if (busta.versione() != versioneLetta) {
            throw new ComandoNonValido(busta.tipo() + " versione " + busta.versione() + " non supportata: Pagamenti"
                    + " legge la versione " + versioneLetta + " (eventId " + busta.eventId() + ")");
        }
        try {
            return json.treeToValue(busta.payload(), classe);
        } catch (JsonProcessingException | IllegalArgumentException errore) {
            throw new ComandoNonValido("Payload di " + busta.tipo() + " non valido (eventId " + busta.eventId()
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
            throw new ComandoNonValido(busta.tipo() + " senza " + String.join(", ", mancanti) + " (eventId "
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
        throw new ComandoNonValido("Busta senza " + campo + " valido");
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
        throw new ComandoNonValido("Busta senza " + campo + " valido");
    }

    /** {eventId, tipo, versione, occorsoIl, payload}: uguale per eventi e comandi. */
    record Busta(UUID eventId, String tipo, int versione, Instant occorsoIl, JsonNode payload) {
    }

    /**
     * RichiestaPagamento v1, da Edizioni: addebitare una prenotazione.
     *
     * @param importoCentesimi 2 bottiglie a 45,00 euro -> 9000
     * @param metodo           la carta di prova scelta nel menu: pm_card_visa o pm_card_chargeDeclined
     */
    record RichiestaPagamentoV1(UUID prenotazioneId, UUID edizioneId, String acquirente, Long importoCentesimi,
                                String valuta, String metodo, String descrizione) {
    }

    /**
     * RichiestaRimborso v1, da Edizioni: restituire per intero il pagamento di
     * una prenotazione (arrivato quando era già scaduta).
     *
     * @param motivo facoltativo, in italiano: finisce nello storico e in RimborsoEseguito
     */
    record RichiestaRimborsoV1(UUID prenotazioneId, String motivo) {
    }
}
