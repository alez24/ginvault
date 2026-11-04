package it.ginvault.gateway.sicurezza;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;

/**
 * Cosa succede al termine del login, quando il provider ci ha detto chi è l'utente.
 *
 * Spring Security usa uno "user service" per caricare il profilo dal provider.
 * Qui ne definiamo due che delegano a quelli standard e poi SOSTITUISCONO le
 * authority con i ruoli GinVault. I ruoli finiscono così nella sessione: non
 * vengono ricalcolati a ogni richiesta.
 *  - Google usa OpenID Connect  -> ReactiveOAuth2UserService<OidcUserRequest, OidcUser>
 *  - GitHub usa OAuth2 "puro"   -> ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User>
 * Spring Security trova i due bean per tipo e li usa in oauth2Login().
 */
@Configuration
class UtentiOAuth2Config {

    /** Google (OIDC): l'identità è l'email, ma solo se Google l'ha verificata. */
    @Bean
    ReactiveOAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(AssegnazioneRuoli ruoli) {
        OidcReactiveOAuth2UserService standard = new OidcReactiveOAuth2UserService();
        return richiesta -> standard.loadUser(richiesta).map(utente -> {
            ClientRegistration registrazione = richiesta.getClientRegistration();
            String identita = Boolean.TRUE.equals(utente.getEmailVerified())
                    ? registrazione.getRegistrationId() + ":" + utente.getEmail()
                    : null; // email non verificata: non la usiamo per dare ruoli
            String attributoNome = attributoNome(registrazione, IdTokenClaimNames.SUB);
            return new DefaultOidcUser(ruoli.autoritaDi(identita), utente.getIdToken(),
                    utente.getUserInfo(), attributoNome);
        });
    }

    /** GitHub (OAuth2): l'identità è il login (es. github:mrossi). */
    @Bean
    ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService(AssegnazioneRuoli ruoli) {
        DefaultReactiveOAuth2UserService standard = new DefaultReactiveOAuth2UserService();
        return richiesta -> standard.loadUser(richiesta).map(utente -> {
            ClientRegistration registrazione = richiesta.getClientRegistration();
            Object login = utente.getAttribute("login");
            String identita = login == null ? null : registrazione.getRegistrationId() + ":" + login;
            String attributoNome = attributoNome(registrazione, "id");
            return new DefaultOAuth2User(ruoli.autoritaDi(identita), utente.getAttributes(), attributoNome);
        });
    }

    /** Attributo che identifica l'utente presso il provider (Google: "sub", GitHub: "id"). */
    private static String attributoNome(ClientRegistration registrazione, String predefinito) {
        String nome = registrazione.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        return StringUtils.hasText(nome) ? nome : predefinito;
    }
}
