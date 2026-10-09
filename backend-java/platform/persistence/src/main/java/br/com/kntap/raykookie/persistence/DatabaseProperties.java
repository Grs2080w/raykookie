package br.com.kntap.raykookie.persistence;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Coordenadas do banco ({@code rayka.db.*}). Sem URL a aplicação não sobe.
 *
 * <p>A URL é JDBC ({@code jdbc:postgresql://host:porta/banco}). Em produção vem do {@code DATABASE_URL}
 * do Node ({@code postgres://…}), convertido pelo {@link DatabaseUrlEnvironmentPostProcessor}.
 *
 * @param url      URL JDBC do Postgres (obrigatória)
 * @param username usuário (opcional quando já vem na URL)
 * @param password senha (opcional quando já vem na URL)
 */
@Validated
@ConfigurationProperties("rayka.db")
public record DatabaseProperties(
        @NotBlank(message = "rayka.db.url é obrigatória (URL JDBC do Postgres)") String url,
        String username,
        String password) {
}
