package br.com.kntap.raykookie.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara quem pode chamar um método de controller. Obrigatória em toda rota: o
 * {@link AccessInterceptor} recusa com 500 a rota que não a tiver, e o {@code AccessInventoryTest}
 * falha no build.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Access {

    /**
     * Nível de acesso exigido.
     *
     * @return o nível
     */
    AccessLevel value();

    /**
     * Mensagem do 401 quando falta a sessão; vazio = a mensagem padrão do nível (a do Node).
     *
     * @return a mensagem
     */
    String unauthorizedMessage() default "";
}
