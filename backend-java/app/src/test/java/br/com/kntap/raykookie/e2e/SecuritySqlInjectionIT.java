package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segurança, categoria 1: injeção de SQL. Payloads clássicos nos parâmetros de URL, nos caminhos, nos corpos e no login;
 * a aplicação tem que tratá-los como texto (nunca 500, nunca erro de banco no corpo, nunca atraso por {@code SLEEP}) e as
 * tabelas continuam lá.
 */
class SecuritySqlInjectionIT extends AppItSupport {

    /** Payloads clássicos: lógico, empilhado, UNION e cego por tempo. */
    static final List<String> PAYLOADS = List.of(
            "' OR '1'='1",
            "'; DROP TABLE users;--",
            "' UNION SELECT username, password_hash FROM users--",
            "1; SELECT pg_sleep(5)--",
            "' || (SELECT pg_sleep(5)) || '",
            "x' AND (SELECT 1 FROM pg_sleep(5))--",
            "\" OR \"\"=\"",
            "1) OR (1=1",
            "\\'; SELECT 1;--");

    /** Pedaços que só aparecem quando o banco vaza erro para a resposta. */
    static final List<String> DATABASE_LEAKS = List.of("org.postgresql", "PSQLException", "syntax error at or near",
            "SQLSyntaxErrorException", "JDBC", "pg_sleep", "relation \"");

    /**
     * Parâmetros de URL e caminhos que aceitam texto livre, com a sessão da dona e sem sessão.
     */
    @Test
    @DisplayName("SQLi: payloads em query e caminho nunca dão 500, vazam erro do banco nem atrasam; tabelas intactas")
    void urlParametersAreInert() {
        String owner = panelSession(OWNER);
        long usersBefore = count("users");
        for (String payload : PAYLOADS) {
            String q = URLEncoder.encode(payload, StandardCharsets.UTF_8).replace("+", "%20");
            for (String path : List.of(
                    "/api/devolucoes?status=" + q,
                    "/api/categories?kind=" + q,
                    "/api/engagement/overview?from=" + q + "&to=" + q + "&platform=" + q,
                    "/api/engagement/funnel?from=" + q + "&platform=" + q,
                    "/api/engagement/pages?to=" + q,
                    "/api/engagement/sources?platform=" + q,
                    "/api/engagement/recipes?sort=" + q,
                    "/api/insights/goals?month=" + q,
                    "/api/fotos/" + q + "?v=" + q,
                    "/api/legal/" + q,
                    "/api/story/produto/" + q,
                    "/api/downloads/" + q)) {
                for (String token : new String[] {owner, null}) {
                    long start = System.nanoTime();
                    Response response = token == null ? api().get(path) : as(token).get(path);
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    assertThat(response.statusCode()).as("%s com %s", path, payload).isLessThan(500);
                    assertThat(response.asString()).as("%s com %s", path, payload).doesNotContain(DATABASE_LEAKS);
                    assertThat(millis).as("%s com %s: pg_sleep(5) não pode ter rodado", path, payload)
                            .isLessThan(4_000);
                }
            }
        }
        assertThat(count("users")).isEqualTo(usersBefore);
    }

    /**
     * Corpos: o texto entra como valor, nunca como SQL, e volta idêntico.
     */
    @Test
    @DisplayName("SQLi: nome com aspas e DROP TABLE é gravado como texto e devolvido igual; tabela segue existindo")
    void bodiesAreStoredAsText() {
        String owner = panelSession(OWNER);
        String evil = "x'); DROP TABLE categories;--";
        Response created = as(owner).body(Map.of("name", evil, "kind", "ingredient")).post("/api/categories");
        assertThat(created.statusCode()).isLessThan(500);
        assertThat(count("categories")).isGreaterThanOrEqualTo(0);
        if (created.statusCode() < 300) {
            assertThat(as(owner).get("/api/categories").jsonPath().getList("name")).contains(evil);
        }

        String coupon = as(owner).body(Map.of("code", "A'OR'1'='1", "percent", 5)).post("/api/coupons").asString();
        assertThat(coupon).doesNotContain(DATABASE_LEAKS);
    }

    /**
     * Login: o clássico {@code ' OR '1'='1} não entra, nem como usuário nem como senha, nem como cliente.
     */
    @Test
    @DisplayName("SQLi: login da dona e do cliente com ' OR '1'='1 recusa com 401/400, sem 500")
    void loginIsNotBypassable() {
        for (String payload : PAYLOADS) {
            Response owner = api().header("x-client", "mobile")
                    .body(Map.of("username", OWNER, "password", payload)).post("/api/auth/login");
            assertThat(owner.statusCode()).as("senha %s", payload).isIn(400, 401, 429);
            Response ownerUser = api().header("x-client", "mobile")
                    .body(Map.of("username", payload, "password", PASSWORD)).post("/api/auth/login");
            assertThat(ownerUser.statusCode()).as("usuário %s", payload).isIn(400, 401, 429);
            Response customer = api().body(Map.of("email", payload, "password", payload)).post("/api/cliente/login");
            assertThat(customer.statusCode()).as("cliente %s", payload).isIn(400, 401, 429);
            assertThat(owner.asString() + ownerUser.asString() + customer.asString()).doesNotContain(DATABASE_LEAKS);
            jdbc.sql("DELETE FROM rate_limits").update();
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
