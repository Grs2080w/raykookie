package br.com.kntap.raykookie.audit;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Lê o histórico para o app de gestão: mais recentes primeiro, paginado, com filtros e busca. O texto livre nunca vai
 * para dentro do SQL: só pedaços fixos de condição são somados, e os valores entram como parâmetros.
 */
@Component
public class JdbcAuditReader {

    /** Máximo de linhas por página. */
    public static final int MAX_PER_PAGE = 100;

    /** Linhas por página quando o app não diz. */
    public static final int DEFAULT_PER_PAGE = 30;

    /** Colunas devolvidas. */
    private static final String COLUMNS = """
            id, occurred_at, user_id, user_name, role, action, category, location, route,
            record_kind AS record_type, record_id,
            old_value::text AS old_value, new_value::text AS new_value, ip, details""";

    /** Busca livre: resumo, usuário, tela e registro. */
    private static final String SEARCH = """
            (details ILIKE :q ESCAPE '\\' OR user_name ILIKE :q ESCAPE '\\' OR location ILIKE :q ESCAPE '\\'
             OR record_id ILIKE :q ESCAPE '\\' OR record_kind ILIKE :q ESCAPE '\\')""";

    /** Banco. */
    private final JdbcClient jdbc;

    /** JSON dos valores antes/depois. */
    private final ObjectMapper json;

    /**
     * Cria o leitor.
     *
     * @param jdbc cliente JDBC
     * @param json JSON
     */
    public JdbcAuditReader(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Filtros do histórico.
     *
     * @param from     a partir de (inclusive), ISO-8601
     * @param to       até (exclusive), ISO-8601
     * @param category categoria
     * @param action   ação
     * @param user     nome do usuário (parte do nome) ou id
     * @param query    busca no resumo, no registro, no usuário e na tela
     */
    public record Filter(String from, String to, String category, String action, String user, String query) {
    }

    /** Condição pronta: pedaços fixos de SQL e os valores como parâmetros. */
    private record Where(String sql, Map<String, Object> params) {
    }

    /**
     * Uma página do histórico da dona.
     *
     * @param owner   dona
     * @param filter  filtros
     * @param page    página, a partir de 1
     * @param perPage linhas por página
     * @return {@code items}, {@code total}, {@code page}, {@code per_page} e as opções de filtro
     */
    public Map<String, Object> page(OwnerId owner, Filter filter, int page, int perPage) {

        // ── 1. Limites da página e condições.
        int size = perPage <= 0 ? DEFAULT_PER_PAGE : Math.min(perPage, MAX_PER_PAGE);
        int number = Math.max(page, 1);
        Where where = where(owner, filter);

        // ── 2. Total e a página pedida.
        long total = jdbc.sql("SELECT count(*) FROM audit_log WHERE " + where.sql()).params(where.params())
                .query(Long.class).single();
        List<Map<String, Object>> items = jdbc.sql("SELECT " + COLUMNS + " FROM audit_log WHERE " + where.sql()
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset").params(where.params())
                .param("limit", size).param("offset", (long) (number - 1) * size).query(this::item).list();

        // ── 3. Resposta: a página, o total e as opções para montar os filtros no app.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", number);
        result.put("per_page", size);
        result.put("filters", Map.of("categories", distinct(owner, "category"), "actions", distinct(owner, "action"),
                "users", distinct(owner, "user_name")));
        return result;
    }

    /**
     * Monta as condições a partir dos filtros: pedaços fixos; os valores vão como parâmetros.
     *
     * @param owner  dona
     * @param filter filtros
     * @return a condição
     */
    private Where where(OwnerId owner, Filter filter) {

        // ── 1. Sempre só o histórico da dona.
        List<String> sql = new ArrayList<>(List.of("owner_id = :owner"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("owner", owner.value());

        // ── 2. Período, categoria e ação.
        if (given(filter.from())) {
            sql.add("occurred_at >= :from");
            params.put("from", instant(filter.from(), "from"));
        }
        if (given(filter.to())) {
            sql.add("occurred_at < :to");
            params.put("to", instant(filter.to(), "to"));
        }
        if (given(filter.category())) {
            sql.add("category = :category");
            params.put("category", filter.category());
        }
        if (given(filter.action())) {
            sql.add("action = :action");
            params.put("action", filter.action());
        }

        // ── 3. Usuário e busca livre (texto vira parâmetro, nunca SQL).
        if (given(filter.user())) {
            sql.add("(user_name ILIKE :user ESCAPE '\\' OR CAST(user_id AS text) = :userId)");
            params.put("user", like(filter.user()));
            params.put("userId", filter.user().trim());
        }
        if (given(filter.query())) {
            sql.add(SEARCH);
            params.put("q", like(filter.query()));
        }

        // ── 4. Junta as condições.
        return new Where(String.join(" AND ", sql), params);
    }

    /**
     * Uma linha do histórico como o app a recebe.
     *
     * @param rs  linha do banco
     * @param row número da linha
     * @return os campos
     * @throws SQLException se o banco falhar
     */
    private Map<String, Object> item(ResultSet rs, int row) throws SQLException {

        // ── 1. Quando, quem, o quê e onde.
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getLong("id"));
        item.put("occurred_at", rs.getObject("occurred_at", OffsetDateTime.class).toString());
        item.put("user_id", rs.getLong("user_id"));
        item.put("user_name", rs.getString("user_name"));
        item.put("role", rs.getString("role"));
        item.put("action", rs.getString("action"));
        item.put("category", rs.getString("category"));
        item.put("location", rs.getString("location"));
        item.put("route", rs.getString("route"));

        // ── 2. O registro afetado, o antes e o depois, a origem e o resumo.
        item.put("record_type", rs.getString("record_type"));
        item.put("record_id", rs.getString("record_id"));
        item.put("old_value", parse(rs.getString("old_value")));
        item.put("new_value", parse(rs.getString("new_value")));
        item.put("ip", rs.getString("ip"));
        item.put("details", rs.getString("details"));
        return item;
    }

    /**
     * Valores distintos de uma coluna, para montar os filtros do app.
     *
     * @param owner  dona
     * @param column coluna (fixa, nunca vinda da requisição)
     * @return os valores em ordem
     */
    private List<String> distinct(OwnerId owner, String column) {
        return jdbc.sql("SELECT DISTINCT " + column + " FROM audit_log WHERE owner_id = :owner ORDER BY 1")
                .param("owner", owner.value()).query(String.class).list();
    }

    /**
     * O filtro veio preenchido?
     *
     * @param value texto do filtro
     * @return verdadeiro se tem conteúdo
     */
    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Data/hora ISO-8601.
     *
     * @param value texto
     * @param field nome do parâmetro, para a mensagem
     * @return o instante
     */
    private static OffsetDateTime instant(String value, String field) {
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            throw new InvalidInputException("Data inválida em " + field + ".");
        }
    }

    /**
     * Padrão do {@code ILIKE}: o que a pessoa digitou vale como texto, sem curingas.
     *
     * @param text texto digitado
     * @return {@code %texto%} com {@code % _ \} escapados
     */
    private static String like(String text) {
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * Texto JSON de uma coluna {@code jsonb}.
     *
     * @param text o texto, ou {@code null}
     * @return o objeto lido, ou {@code null}
     */
    private Object parse(String text) {
        return text == null ? null : json.readValue(text, Object.class);
    }
}
