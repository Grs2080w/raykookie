package br.com.kntap.raykookie.customers.adapter.out.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Endereços do Google ({@code rayka.google.*-url}); os testes trocam pelo WireMock.
 */
@Configuration(proxyBeanMethods = false)
public class GoogleConfig {

    /**
     * Os endereços.
     *
     * @param auth     tela de login
     * @param token    troca do código
     * @param userinfo perfil
     * @param jwks     chaves públicas
     * @return os endereços
     */
    @Bean
    public GoogleClient.Endpoints googleEndpoints(
            @Value("${rayka.google.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String auth,
            @Value("${rayka.google.token-url:https://oauth2.googleapis.com/token}") String token,
            @Value("${rayka.google.userinfo-url:https://openidconnect.googleapis.com/v1/userinfo}") String userinfo,
            @Value("${rayka.google.jwks-url:https://www.googleapis.com/oauth2/v3/certs}") String jwks) {
        return new GoogleClient.Endpoints(auth, token, userinfo, jwks);
    }
}
