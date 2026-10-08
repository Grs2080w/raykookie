package br.com.kntap.raykookie.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração de acesso ({@code rayka.*}).
 *
 * @param shopOwner           nome de usuário da dona da loja ({@code SHOP_OWNER}); obrigatório
 * @param registrationEnabled cadastro de dona aberto ({@code REGISTRATION_ENABLED}); padrão {@code false}
 */
@ConfigurationProperties("rayka")
public record SecurityProperties(String shopOwner, @DefaultValue("false") String registrationEnabled) {

    /**
     * Cadastro de dona aberto (só vale com a tabela {@code users} vazia; G01).
     *
     * @return verdadeiro com {@code REGISTRATION_ENABLED=true}
     */
    public boolean registrationOpen() {
        return "true".equals(registrationEnabled);
    }
}
