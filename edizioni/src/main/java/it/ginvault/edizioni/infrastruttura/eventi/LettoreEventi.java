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
 * Legge i messaggi di produzione.eventi e pagamenti.esiti: la busta
 * (docs/eventi.md) e i payload di ResaRicalcolata, PagamentoRiuscito,
 * PagamentoFallito e RimborsoEseguito. Come in Produzione:
 *
 * "Tolerant reader": i campi in più si ignorano (Produzione può aggiungerne
 * senza rompere niente, come lottoId nel passo 4), quelli che servono devono
 * esserci. Un messaggio sbagliato diventa EventoNonValido, che va nel dead
 * letter topic.
 *
 * I record qui sotto sono la COPIA di Edizioni dei contratti: nessuna
 * dipendenza Java da Produzione o da Pagamenti, conta solo il JSON.
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

    // ---------------------------------------------------------------- gli esiti di Pagamenti (pagamenti.esiti)

    /** PagamentoRiuscito v1: servono la prenotazione e il pagamento. */
    PagamentoRiuscitoV1 pagamentoRiuscito(Busta busta) {
        PagamentoRiuscitoV1 esito = payload(busta, PagamentoRiuscitoV1.class);
        richiesti(busta, "prenotazioneId", esito.prenotazioneId(), "pagamentoId", esito.pagamentoId());
        return esito;
    }

    /** PagamentoFallito v1: serve la prenotazione; motivo e spiegazione sono per i log e per chi ha prenotato. */
    PagamentoFallitoV1 pagamentoFallito(Busta busta) {
        PagamentoFallitoV1 esito = payload(busta, PagamentoFallitoV1.class);
        richiesti(busta, "prenotazioneId", esito.prenotazioneId());
        return esito;
    }

    /** RimborsoEseguito v1: serve la prenotazione. */
    RimborsoEseguitoV1 rimborsoEseguito(Busta busta) {
        RimborsoEseguitoV1 esito = payload(busta, RimborsoEseguitoV1.class);
        richiesti(busta, "prenotazioneId", esito.prenotazioneId());
        return esito;
    }

    /** Il payload della versione 1, come record; un'altra versione o un JSON sbagliato -> EventoNonValido. */
    private <T> T payload(Busta busta, Class<T> classe) {
        if (busta.versione() != 1) {
            throw new EventoNonValido(busta.tipo() + " versione " + busta.versione() + " non supportata: Edizioni"
                    + " legge la versione 1 (eventId " + busta.eventId() + ")");
        }
        try {
            return lettore.treeToValue(busta.payload(), classe);
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

    /** ResaRicalcolata v1, da produzione.eventi (solo i campi che servono a Edizioni). */
    record ResaRicalcolataV1(UUID pianoId, UUID edizioneId, String botanica, BigDecimal kgPrevisti,
                             BigDecimal kgDisponibiliPrima, BigDecimal kgDisponibili, String motivo) {
    }

    /** PagamentoRiuscito v1, da pagamenti.esiti. riferimentoStripe: l'id del PaymentIntent (pi_...). */
    record PagamentoRiuscitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                               Long importoCentesimi, String valuta, String riferimentoStripe) {
    }

    /**
     * PagamentoFallito v1, da pagamenti.esiti. motivo come testo e non come
     * enum: un motivo nuovo di Pagamenti non rende il messaggio illeggibile.
     */
    record PagamentoFallitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                              String motivo, String codice, String spiegazione) {
    }

    /** RimborsoEseguito v1, da pagamenti.esiti. riferimentoStripe: l'id del rimborso (re_...). */
    record RimborsoEseguitoV1(UUID prenotazioneId, UUID pagamentoId, UUID edizioneId, String acquirente,
                              Long importoCentesimi, String valuta, String riferimentoStripe, String motivo) {
    }
}
