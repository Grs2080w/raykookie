package br.com.kntap.raykookie.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Liga a configuração validada ({@link RaykookieProperties}): um valor inválido impede a subida.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RaykookieProperties.class)
public class RaykookieConfig {
}
