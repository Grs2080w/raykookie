package br.com.kntap.raykookie.identity;

import br.com.kntap.raykookie.identity.api.ScryptPasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga o módulo {@code identity}.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfig {

    /**
     * Senhas no formato do Node (também usado por {@code customers}).
     *
     * @return o hasher
     */
    @Bean
    public ScryptPasswordHasher scryptPasswordHasher() {
        return new ScryptPasswordHasher();
    }
}
