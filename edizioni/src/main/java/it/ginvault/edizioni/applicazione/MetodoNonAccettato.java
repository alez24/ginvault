package it.ginvault.edizioni.applicazione;

import java.util.List;

/** Un metodo di pagamento che non è nel menu. Diventa un 400 con l'elenco di quelli accettati. */
public class MetodoNonAccettato extends RuntimeException {

    private final List<String> accettati;

    public MetodoNonAccettato(String metodo, List<String> accettati) {
        super("Il metodo di pagamento \"" + metodo + "\" non si può usare: si sceglie tra "
                + String.join(", ", accettati));
        this.accettati = List.copyOf(accettati);
    }

    public List<String> accettati() {
        return accettati;
    }
}
