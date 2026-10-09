package br.com.kntap.raykookie.testing.architecture.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Imitação do {@code @Access} real (Tarefa 2.5): a regra reconhece a anotação pelo nome simples.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Access {
}
