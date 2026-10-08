package br.com.kntap.raykookie.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Liga a configuração da borda HTTP comum ({@link WebProperties}). Filtros, tratador de erros e a
 * página de erro em JSON são componentes deste pacote.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
public class WebConfig {
}
