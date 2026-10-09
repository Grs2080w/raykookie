package br.com.kntap.raykookie.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga a configuração da borda HTTP comum ({@link WebProperties}) e o resolvedor do IP do cliente.
 * Filtros, tratador de erros e a página de erro em JSON são componentes deste pacote.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
public class WebConfig {

    /**
     * Resolvedor do IP do cliente com os proxies confiáveis da configuração. Uma faixa inválida impede
     * o servidor de subir.
     *
     * @param properties configuração da borda HTTP
     * @return o resolvedor
     */
    @Bean
    public ClientIpResolver clientIpResolver(WebProperties properties) {
        return new ClientIpResolver(TrustedProxies.parse(properties.trustedProxies()));
    }
}
