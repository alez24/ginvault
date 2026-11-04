package it.ginvault.gateway.sicurezza;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Decide i ruoli GinVault di un utente.
 *
 * Google e GitHub dicono CHI è l'utente, non COSA può fare: i ruoli sono nostri.
 * L'identità è una stringa "provider:valore" (google:email oppure github:login),
 * confrontata senza distinguere maiuscole e minuscole.
 * Chi non compare in nessun elenco riceve il ruolo predefinito CLIENTE.
 *
 * Nota di dominio: SOCIO dà accesso all'area soci. Il diritto di prelazione su
 * un'edizione NON è un ruolo: lo verifica il servizio Edizioni (mock del Club).
 */
@Component
public class AssegnazioneRuoli {

    public static final String RUOLO_PREDEFINITO = "CLIENTE";

    private final Map<String, SortedSet<String>> ruoliPerIdentita = new HashMap<>();

    public AssegnazioneRuoli(RuoliProperties elenchi) {
        aggiungi("SOCIO", elenchi.socio());
        aggiungi("DISTILLATORE", elenchi.distillatore());
        aggiungi("RESP_ACQUISTI", elenchi.respAcquisti());
        aggiungi("ADMIN", elenchi.admin());
    }

    /** Ruoli dell'identità indicata; null = identità non verificabile -> solo CLIENTE. */
    public SortedSet<String> ruoliDi(String identita) {
        SortedSet<String> ruoli = ruoliPerIdentita.get(normalizza(identita));
        if (ruoli == null || ruoli.isEmpty()) {
            return Collections.unmodifiableSortedSet(new TreeSet<>(List.of(RUOLO_PREDEFINITO)));
        }
        return Collections.unmodifiableSortedSet(ruoli);
    }

    /** Gli stessi ruoli come authority di Spring Security: SOCIO -> ROLE_SOCIO. */
    public List<GrantedAuthority> autoritaDi(String identita) {
        return ruoliDi(identita).stream()
                .<GrantedAuthority>map(ruolo -> new SimpleGrantedAuthority("ROLE_" + ruolo))
                .toList();
    }

    private void aggiungi(String ruolo, List<String> identita) {
        if (identita == null) {
            return;
        }
        for (String voce : identita) {
            String chiave = normalizza(voce);
            if (!chiave.isEmpty()) {
                ruoliPerIdentita.computeIfAbsent(chiave, k -> new TreeSet<>()).add(ruolo);
            }
        }
    }

    private static String normalizza(String identita) {
        return identita == null ? "" : identita.trim().toLowerCase(Locale.ROOT);
    }
}
