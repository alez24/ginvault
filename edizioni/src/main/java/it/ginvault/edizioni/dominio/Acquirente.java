package it.ginvault.edizioni.dominio;

/**
 * Value Object: chi prenota, preso dal JWT del gateway.
 *
 * @param id    l'identità stabile dell'utente: il claim "sub" (es. "github:1234567")
 * @param socio true se ha il ruolo SOCIO: solo i soci prenotano in prelazione
 */
public record Acquirente(String id, boolean socio) {

    public Acquirente {
        if (id == null || id.isBlank() || id.length() > 100) {
            throw new IllegalArgumentException("Acquirente senza un'identità valida: " + id);
        }
    }
}
