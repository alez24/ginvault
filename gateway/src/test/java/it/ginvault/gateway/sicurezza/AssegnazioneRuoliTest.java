package it.ginvault.gateway.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class AssegnazioneRuoliTest {

    private final AssegnazioneRuoli ruoli = new AssegnazioneRuoli(new RuoliProperties(
            List.of("github:socio1", " Google:Mario.Rossi@Gmail.com "),
            List.of("github:distillatrice"),
            List.of(),
            List.of("github:admin", "github:socio1")));

    @Test
    void chiNonEInNessunElencoEUnCliente() {
        assertThat(ruoli.ruoliDi("github:sconosciuto")).containsExactly("CLIENTE");
    }

    @Test
    void identitaNonVerificabileEUnCliente() {
        assertThat(ruoli.ruoliDi(null)).containsExactly("CLIENTE");
    }

    @Test
    void unUtentePuoAvereRuoliMultipli() {
        assertThat(ruoli.ruoliDi("github:socio1")).containsExactly("ADMIN", "SOCIO");
    }

    @Test
    void ilConfrontoIgnoraMaiuscoleESpazi() {
        assertThat(ruoli.ruoliDi("google:mario.rossi@gmail.com")).containsExactly("SOCIO");
    }

    @Test
    void leAuthorityHannoIlPrefissoRole() {
        assertThat(ruoli.autoritaDi("github:distillatrice"))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DISTILLATORE");
    }

    @Test
    void elenchiNonImpostatiDannoSoloClienti() {
        AssegnazioneRuoli vuota = new AssegnazioneRuoli(new RuoliProperties(null, null, null, null));
        assertThat(vuota.ruoliDi("github:admin")).containsExactly("CLIENTE");
    }
}
