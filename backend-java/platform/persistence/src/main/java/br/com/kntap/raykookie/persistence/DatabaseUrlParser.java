package br.com.kntap.raykookie.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Converte o {@code DATABASE_URL} do Node ({@code postgres://usuario:senha@host:porta/banco?opções}) nas
 * coordenadas JDBC que o Java usa, para o mesmo {@code .env} servir aos dois durante o corte.
 */
public final class DatabaseUrlParser {

    /** Porta padrão do Postgres. */
    private static final int DEFAULT_PORT = 5432;

    /**
     * Sem instâncias.
     */
    private DatabaseUrlParser() {
    }

    /**
     * Converte a URL.
     *
     * @param databaseUrl {@code postgres://…} ou {@code postgresql://…}
     * @return URL JDBC, usuário e senha (decodificados)
     * @throws IllegalArgumentException se a URL não for de Postgres ou estiver incompleta (o servidor não sobe)
     */
    public static Coordinates parse(String databaseUrl) {

        // ── 1. Lê a URL e confere o esquema.
        URI uri = toUri(databaseUrl);
        String scheme = String.valueOf(uri.getScheme());
        if (!scheme.equals("postgres") && !scheme.equals("postgresql")) {
            throw new IllegalArgumentException(
                    "DATABASE_URL precisa começar com postgres:// (veio " + scheme + "://).");
        }
        if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
            throw new IllegalArgumentException("DATABASE_URL precisa ter host e nome do banco.");
        }

        // ── 2. Monta a URL JDBC, mantendo as opções (ex.: sslmode).
        int port = uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query;

        // ── 3. Usuário e senha vêm codificados na URL (ex.: %40 para @).
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null) {
            return new Coordinates(jdbcUrl, null, null);
        }
        int colon = userInfo.indexOf(':');
        String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
        String password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        return new Coordinates(jdbcUrl, decode(user), password);
    }

    /**
     * Lê a URL sem deixar a senha vazar na mensagem de erro.
     *
     * @param databaseUrl a URL
     * @return a URL lida
     * @throws IllegalArgumentException se não for uma URL
     */
    private static URI toUri(String databaseUrl) {
        try {
            return new URI(databaseUrl.strip());
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("DATABASE_URL não é uma URL válida.");
        }
    }

    /**
     * Decodifica um trecho da URL.
     *
     * @param text trecho codificado
     * @return o texto
     */
    private static String decode(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * Coordenadas JDBC.
     *
     * @param jdbcUrl  URL JDBC
     * @param username usuário, ou nulo
     * @param password senha, ou nula
     */
    public record Coordinates(String jdbcUrl, String username, String password) {
    }
}
