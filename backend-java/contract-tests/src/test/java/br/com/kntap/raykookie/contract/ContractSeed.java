package br.com.kntap.raykookie.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Cria dados de teste direto no banco descartável do servidor sob teste, no mesmo formato que o Node
 * grava. Cada método devolve o que o teste precisa para agir como aquele usuário (o token de sessão).
 *
 * <p>Os dados de domínio ({@code recipeOnMenu}, {@code shopOrder}, {@code payLink}) entram com as tarefas
 * de módulo que os usam (Fases 4–6), escritos contra o esquema real das migrations.
 */
public final class ContractSeed {

    /** Senha de todas as contas criadas aqui. */
    public static final String PASSWORD = "senha-de-teste-123";

    /**
     * Hash scrypt de {@link #PASSWORD} no formato do Node (vetor de ouro do Apêndice B do plano):
     * N=16384, r=8, p=1, 64 bytes, sal {@code 00112233445566778899aabbccddeeff}.
     */
    public static final String PASSWORD_HASH = "scrypt$00112233445566778899aabbccddeeff$"
            + "216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a4"
            + "2dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff";

    /** Gerador de tokens. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Banco do servidor sob teste. */
    private final String dbUrl;

    /**
     * Cria o semeador.
     *
     * @param target servidor e banco sob teste
     */
    public ContractSeed(ContractTarget target) {
        this.dbUrl = target.dbUrl();
    }

    /**
     * Uma conta do painel com sessão aberta (a dona, se o nome for o {@code SHOP_OWNER} do servidor).
     *
     * @param username usuário
     * @return id e token da sessão
     */
    public Session owner(String username) {
        try (Connection db = connect()) {
            long id = upsertId(db, "INSERT INTO users (username, password_hash) VALUES (?, ?) "
                    + "ON CONFLICT (lower(username)) DO UPDATE SET password_hash = EXCLUDED.password_hash RETURNING id",
                    username, PASSWORD_HASH);
            update(db, "INSERT INTO settings (user_id) VALUES (?) ON CONFLICT DO NOTHING", id);
            String token = newToken();
            update(db, "INSERT INTO sessions (user_id, token_hash, expires_at) "
                    + "VALUES (?, ?, now() + interval '30 days')", id, sha256(token));
            return new Session(id, token);
        } catch (SQLException error) {
            throw new IllegalStateException("Não consegui criar a conta " + username, error);
        }
    }

    /**
     * Um cliente da loja com senha e sessão aberta.
     *
     * @param email e-mail do cliente
     * @return id e token da sessão
     */
    public Session customer(String email) {
        try (Connection db = connect()) {
            long id = existingCustomer(db, email);
            if (id == 0) {
                id = upsertId(db, "INSERT INTO customers (email, name, password_hash) VALUES (?, ?, ?) RETURNING id",
                        email, "Cliente de Teste", PASSWORD_HASH);
            }
            String token = newToken();
            update(db, "INSERT INTO customer_sessions (token_hash, customer_id, expires_at) "
                    + "VALUES (?, ?, now() + interval '30 days')", sha256(token), id);
            return new Session(id, token);
        } catch (SQLException error) {
            throw new IllegalStateException("Não consegui criar o cliente " + email, error);
        }
    }

    /**
     * Um cliente que só entra pelo Google (sem senha), com sessão aberta.
     *
     * @param email e-mail
     * @return id e token da sessão
     */
    public Session googleCustomer(String email) {
        try (Connection db = connect()) {
            long id = upsertId(db, "INSERT INTO customers (email, name, google_sub) VALUES (?, ?, ?) RETURNING id",
                    email, "Cliente Google", "google-" + newToken());
            String token = newToken();
            update(db, "INSERT INTO customer_sessions (token_hash, customer_id, expires_at) "
                    + "VALUES (?, ?, now() + interval '30 days')", sha256(token), id);
            return new Session(id, token);
        } catch (SQLException error) {
            throw new IllegalStateException("Não consegui criar o cliente " + email, error);
        }
    }

    /**
     * Executa um SQL qualquer no banco sob teste (para preparar casos raros).
     *
     * @param sql    comando
     * @param params parâmetros
     */
    public void exec(String sql, Object... params) {
        try (Connection db = connect()) {
            update(db, sql, params);
        } catch (SQLException error) {
            throw new IllegalStateException("Falhou: " + sql, error);
        }
    }

    /**
     * Executa um {@code INSERT … RETURNING id} qualquer no banco sob teste.
     *
     * @param sql    comando
     * @param params parâmetros
     * @return o id criado
     */
    public long insert(String sql, Object... params) {
        try (Connection db = connect()) {
            return upsertId(db, sql, params);
        } catch (SQLException error) {
            throw new IllegalStateException("Falhou: " + sql, error);
        }
    }

    /**
     * Lê um valor (primeira coluna da primeira linha).
     *
     * @param sql    consulta
     * @param params parâmetros
     * @return o valor, ou nulo
     */
    public Object value(String sql, Object... params) {
        try (Connection db = connect(); PreparedStatement statement = prepare(db, sql, params);
                ResultSet row = statement.executeQuery()) {
            return row.next() ? row.getObject(1) : null;
        } catch (SQLException error) {
            throw new IllegalStateException("Falhou: " + sql, error);
        }
    }

    /**
     * Um e-mail que nenhum outro teste usa.
     *
     * @param prefix começo do e-mail
     * @return o e-mail
     */
    public static String uniqueEmail(String prefix) {
        return prefix + "-" + newToken().substring(0, 10).toLowerCase().replaceAll("[^a-z0-9]", "x") + "@teste.com";
    }

    /**
     * Um {@code X-Forwarded-For} que nenhum outro teste usa, para os limites por IP não se misturarem: no Node
     * a chave é o cabeçalho inteiro; no Java, o IP mais à direita (o que o proxy confiável acrescentou).
     *
     * @return o valor do cabeçalho
     */
    public static String uniqueForwardedFor() {
        return "198.51.100." + (1 + RANDOM.nextInt(250)) + ", 203.0." + RANDOM.nextInt(250) + "." + RANDOM.nextInt(250);
    }

    /**
     * Id de um cliente já cadastrado com este e-mail.
     *
     * @param db    conexão
     * @param email e-mail
     * @return o id, ou 0
     * @throws SQLException se a consulta falhar
     */
    private static long existingCustomer(Connection db, String email) throws SQLException {
        try (PreparedStatement select = db.prepareStatement(
                "SELECT id FROM customers WHERE lower(email) = lower(?) AND NOT guest")) {
            select.setString(1, email);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    /**
     * Abre uma conexão com o banco sob teste.
     *
     * @return a conexão
     * @throws SQLException se não conectar
     */
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    /**
     * Executa um INSERT … RETURNING id.
     *
     * @param db     conexão
     * @param sql    comando
     * @param params parâmetros
     * @return o id
     * @throws SQLException se falhar
     */
    private static long upsertId(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, params); ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /**
     * Executa um comando sem retorno.
     *
     * @param db     conexão
     * @param sql    comando
     * @param params parâmetros
     * @throws SQLException se falhar
     */
    private static void update(Connection db, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, params)) {
            statement.executeUpdate();
        }
    }

    /**
     * Prepara um comando com parâmetros.
     *
     * @param db     conexão
     * @param sql    comando
     * @param params parâmetros
     * @return o comando
     * @throws SQLException se falhar
     */
    private static PreparedStatement prepare(Connection db, String sql, Object... params) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }

    /**
     * Token de sessão no formato do Node: 32 bytes em base64url.
     *
     * @return o token
     */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * sha256 em hex minúsculo (como o Node guarda o token).
     *
     * @param text texto
     * @return o hash
     */
    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Uma sessão aberta.
     *
     * @param id    id da conta ou do cliente
     * @param token token para {@code Authorization: Bearer}
     */
    public record Session(long id, String token) {

        /**
         * Valor do cabeçalho {@code Authorization}.
         *
         * @return {@code Bearer <token>}
         */
        public String bearer() {
            return "Bearer " + token;
        }
    }
}
