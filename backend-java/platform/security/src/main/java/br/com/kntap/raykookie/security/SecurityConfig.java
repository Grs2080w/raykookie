package br.com.kntap.raykookie.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga o controle de acesso: resolve a dona na subida e põe o {@link AccessInterceptor} na frente de
 * toda rota.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * A dona da loja, resolvida uma vez na subida (o servidor não sobe sem ela).
     *
     * @param properties configuração com {@code SHOP_OWNER}
     * @param sessions   onde procurar a conta
     * @return a dona
     */
    @Bean
    public ShopOwner shopOwner(SecurityProperties properties, OwnerSessions sessions) {
        return ShopOwner.resolve(properties.shopOwner(), sessions);
    }

    /**
     * Registra o interceptador de acesso em todas as rotas.
     *
     * @param interceptor o interceptador
     * @return a configuração do Spring MVC
     */
    @Bean
    public WebMvcConfigurer accessInterceptorRegistration(AccessInterceptor interceptor) {
        return new WebMvcConfigurer() {

            /**
             * Põe o interceptador na frente de toda rota.
             *
             * @param registry registro de interceptadores
             */
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
