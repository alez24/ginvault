package it.ginvault.pagamenti.dominio;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Value Object: quello che Edizioni chiede di addebitare per una prenotazione
 * (il comando RichiestaPagamento su pagamenti.comandi, docs/eventi.md).
 *
 * @param acquirente       il "sub" di chi ha prenotato (es. "github:1234567"), solo per lo storico
 * @param importoCentesimi l'importo in centesimi, come lo vuole Stripe: 90,00 euro -> 9000
 * @param valuta           codice ISO in minuscolo, come lo vuole Stripe: "eur"
 * @param metodo           il metodo di pagamento di Stripe: in modalità test una carta di prova,
 *                         "pm_card_visa" (va a buon fine) o "pm_card_chargeDeclined" (rifiutata)
 * @param descrizione      la riga che compare nella dashboard di Stripe
 */
public record RichiestaDiPagamento(UUID prenotazioneId, UUID edizioneId, String acquirente, long importoCentesimi,
                                   String valuta, String metodo, String descrizione) {

    /** "pm_" e poi lettere, cifre e trattini bassi: gli id dei metodi di pagamento di Stripe. */
    private static final Pattern METODO = Pattern.compile("pm_[A-Za-z0-9_]{1,60}");
    private static final Pattern VALUTA = Pattern.compile("[a-z]{3}");

    public RichiestaDiPagamento {
        if (prenotazioneId == null || edizioneId == null) {
            throw new IllegalArgumentException("Servono la prenotazione e l'edizione");
        }
        if (acquirente == null || acquirente.isBlank() || acquirente.length() > 100) {
            throw new IllegalArgumentException("Acquirente non valido: " + acquirente);
        }
        if (importoCentesimi <= 0) {
            throw new IllegalArgumentException("L'importo deve essere maggiore di zero: " + importoCentesimi);
        }
        valuta = valuta == null ? null : valuta.toLowerCase(Locale.ROOT);
        if (valuta == null || !VALUTA.matcher(valuta).matches()) {
            throw new IllegalArgumentException("Valuta non valida: " + valuta);
        }
        if (metodo == null || !METODO.matcher(metodo).matches()) {
            throw new IllegalArgumentException("Metodo di pagamento non valido: " + metodo);
        }
        if (descrizione != null && descrizione.length() > 200) {
            descrizione = descrizione.substring(0, 200);
        }
    }
}
