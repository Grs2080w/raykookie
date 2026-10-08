package br.com.kntap.raykookie.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração de acesso ({@code rayka.*}).
 *
 * @param shopOwner nome de usuário da dona da loja ({@code SHOP_OWNER}); obrigatório
 */
@ConfigurationProperties("rayka")
public record SecurityProperties(String shopOwner) {
}
