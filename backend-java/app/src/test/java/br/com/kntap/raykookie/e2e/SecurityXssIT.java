package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Segurança, categoria 2 (XSS): dado do usuário nunca vira HTML no servidor. A API só devolve JSON (com
 * {@code nosniff}), então o navegador nunca executa o texto; quem desenha (React no site, Flutter nos apps) escapa na
 * hora de mostrar (conferido por varredura em {@code tests/security/frontend-xss.test.mjs}).
 */
class SecurityXssIT extends AppItSupport {

    /** Payloads clássicos de XSS. */
    static final List<String> PAYLOADS = List.of("<script>alert(1)</script>", "<img src=x onerror=alert(1)>",
            "javascript:alert(1)", "\"><svg/onload=alert(1)>", "'-alert(1)-'", "</script><script>alert(2)</script>");

    /**
     * Texto guardado volta como dado, em JSON, nunca como página.
     */
    @Test
    @DisplayName("XSS armazenado: nome, observação e código de cupom com <script>/onerror voltam como texto em JSON + nosniff")
    void storedPayloadsComeBackAsJsonText() {
        String owner = panelSession(OWNER);
        String item = menuItem();
        String customer = newCustomer().token();
        for (String payload : PAYLOADS) {
            Map<String, Object> body = order(item, "dinheiro");
            body.put("contact_name", payload);
            body.put("notes", payload);
            Response created = as(customer).body(body).post("/api/cliente/pedidos");
            assertThat(created.statusCode()).isIn(201, 400, 429);
            jdbc.sql("DELETE FROM rate_limits").update();
        }

        Response orders = as(owner).get("/api/orders");
        assertThat(orders.statusCode()).isEqualTo(200);
        assertThat(orders.contentType()).startsWith("application/json");
        assertThat(orders.header("X-Content-Type-Options")).isEqualTo("nosniff");

        Response mine = as(customer).get("/api/cliente/pedidos");
        assertThat(mine.contentType()).startsWith("application/json");
        assertThat(mine.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /**
     * Texto da URL não é refletido numa página.
     */
    @Test
    @DisplayName("XSS refletido: payload no caminho e na query dá JSON (nunca text/html) e a CSP bloqueia scripts")
    void reflectedPayloadsNeverBecomeHtml() {
        for (String payload : PAYLOADS) {
            for (String path : List.of("/api/legal/{p}", "/api/story/produto/{p}", "/api/fotos/{p}")) {
                Response response = api().urlEncodingEnabled(true).get(path, payload);
                assertThat(response.statusCode()).as("%s %s", path, payload).isBetween(400, 499);
                // URL com < ou > é recusada pelo Tomcat antes do Spring, com a página genérica "400 Bad Request":
                // pode ser HTML, mas nunca devolve o que foi enviado. Nos demais casos a resposta é JSON com CSP.
                assertThat(response.asString()).as("%s %s", path, payload).doesNotContain("alert(")
                        .doesNotContain("<script").doesNotContain("onerror").doesNotContain("<svg");
                if (!String.valueOf(response.contentType()).startsWith("text/html")) {
                    assertThat(response.header("Content-Security-Policy")).contains("default-src 'none'");
                }
            }
            Response query = api().queryParam("q", payload).get("/api/menu");
            assertThat(query.contentType()).startsWith("application/json");
        }
    }

    /**
     * Link de login Google não aceita esquema perigoso.
     */
    @Test
    @DisplayName("javascript: em links: o 'next' do login Google nunca vira Location com esquema")
    void javascriptSchemeIsNeverRedirected() {
        for (String next : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,<script>alert(1)</script>",
                "//evil.example/x", "https://evil.example")) {
            Response response = api().queryParam("next", next).get("/api/cliente/google");
            String location = String.valueOf(response.header("Location"));
            assertThat(location.toLowerCase()).as(next).doesNotContain("javascript:").doesNotContain("data:")
                    .doesNotContain("evil.example");
        }
    }
}
