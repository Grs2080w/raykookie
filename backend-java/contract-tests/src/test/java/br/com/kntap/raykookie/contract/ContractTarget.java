package br.com.kntap.raykookie.contract;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * O servidor sob teste e o banco descartável dele, lidos das variáveis de ambiente:
 *
 * <ul>
 *   <li>{@code CONTRACT_BASE_URL}: endereço do servidor (ex.: {@code http://127.0.0.1:10099});</li>
 *   <li>{@code CONTRACT_DB_URL}: URL JDBC do banco que esse servidor usa; precisa terminar em {@code _test}
 *       (a suíte apaga e cria dados nele);</li>
 *   <li>{@code CONTRACT_TARGET}: {@code node} ou {@code java};</li>
 *   <li>{@code CONTRACT_MP_FAKE_PORT}: porta do Mercado Pago falso (o servidor precisa ter
 *       {@code MP_API_BASE=http://127.0.0.1:<porta>});</li>
 *   <li>{@code CONTRACT_SHOP_OWNER}: o {@code SHOP_OWNER} do servidor (padrão {@code rayka}).</li>
 * </ul>
 *
 * @param baseUrl     endereço do servidor
 * @param dbUrl       URL JDBC do banco descartável
 * @param kind        Node ou Java
 * @param mpFakePort  porta do Mercado Pago falso
 * @param shopOwner   usuário da dona no servidor
 */
public record ContractTarget(URI baseUrl, String dbUrl, Kind kind, int mpFakePort, String shopOwner) {

    /** Qual implementação está sendo testada. */
    public enum Kind {

        /** O backend Node atual ({@code backend/}). */
        NODE,

        /** O backend Java ({@code backend-java/}). */
        JAVA
    }

    /**
     * Confere a configuração.
     *
     * @param baseUrl    endereço do servidor
     * @param dbUrl      URL JDBC
     * @param kind       Node ou Java
     * @param mpFakePort porta do falso
     * @param shopOwner  usuário da dona
     */
    public ContractTarget {
        if (!databaseName(dbUrl).endsWith("_test")) {
            throw new IllegalStateException(
                    "CONTRACT_DB_URL precisa apontar para um banco descartável terminado em _test (veio "
                            + databaseName(dbUrl) + "): a suíte apaga e cria dados nele.");
        }
    }

    /**
     * Lê a configuração das variáveis de ambiente.
     *
     * @return o alvo
     * @throws IllegalStateException se faltar variável obrigatória
     */
    public static ContractTarget fromEnvironment() {
        return from(System.getenv());
    }

    /**
     * Lê a configuração de um mapa de variáveis.
     *
     * @param env variáveis
     * @return o alvo
     * @throws IllegalStateException se faltar variável obrigatória
     */
    static ContractTarget from(Map<String, String> env) {
        String kind = required(env, "CONTRACT_TARGET").toUpperCase(Locale.ROOT);
        return new ContractTarget(
                URI.create(required(env, "CONTRACT_BASE_URL").replaceAll("/+$", "")),
                required(env, "CONTRACT_DB_URL"),
                Kind.valueOf(kind),
                Integer.parseInt(env.getOrDefault("CONTRACT_MP_FAKE_PORT", "0")),
                env.getOrDefault("CONTRACT_SHOP_OWNER", "rayka"));
    }

    /**
     * Uma variável obrigatória.
     *
     * @param env  variáveis
     * @param name nome
     * @return o valor
     * @throws IllegalStateException se faltar
     */
    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " não foi definida (ver contract-tests/README.md).");
        }
        return value.strip();
    }

    /**
     * Nome do banco numa URL JDBC ({@code jdbc:postgresql://host:porta/banco?…}).
     *
     * @param jdbcUrl a URL
     * @return o nome do banco
     */
    static String databaseName(String jdbcUrl) {
        String path = URI.create(jdbcUrl.replaceFirst("^jdbc:", "")).getPath();
        return path == null ? "" : path.replaceFirst("^/", "");
    }

    /**
     * Diz se o alvo é o Java.
     *
     * @return verdadeiro com {@code CONTRACT_TARGET=java}
     */
    public boolean isJava() {
        return kind == Kind.JAVA;
    }
}
