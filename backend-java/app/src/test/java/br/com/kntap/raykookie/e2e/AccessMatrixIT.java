package br.com.kntap.raykookie.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Matriz de acesso (T03): cada rota do inventário chamada sem sessão, com sessão de cliente, com a da dona e com a
 * de uma conta do painel que não é a dona; o status tem que bater com a coluna de acesso. E um cliente nunca alcança
 * o pedido, a devolução ou a avaliação de outro (404).
 */
class AccessMatrixIT extends AppItSupport {

    /** Rotas que apagam a sessão ou a conta de quem chama: vão por último. */
    private static final List<String> LAST = List.of("POST /api/auth/logout", "POST /api/cliente/logout",
            "DELETE /api/cliente/me");

    /**
     * Respostas que não são 404 para o recurso de outro cliente, iguais às do Node: desistir de devolução responde
     * 409 "Não há solicitação em análise para cancelar." tanto para pedido alheio quanto para pedido sem
     * solicitação, então não revela nada.
     */
    private static final Map<String, Integer> NOT_404 = Map.of("DELETE /api/cliente/pedidos/{id}/devolucao", 409);

    /** Foto mínima: a assinatura de PNG e zeros até o tamanho mínimo de imagem. */
    private static final byte[] PNG = Arrays.copyOf(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a,
        0x0a}, 48);

    /** Corpo das rotas com corpo: o mínimo para passar da validação até a busca do recurso. */
    private static final Map<String, Object> BODY = Map.of("reason_code", "outro", "reason", "teste de acesso",
            "card_token", "APRO", "card_payment_method_id", "master", "cpf", CPF, "installments", 1);

    /**
     * Cada rota com cada sessão.
     *
     * @throws IOException se o inventário não puder ser lido
     */
    @Test
    @DisplayName("T03: cada rota sem sessão, como cliente, como a dona e como outra conta do painel")
    void everyRouteRespectsItsAccess() throws IOException {

        // ── 1. Ids que não existem: o acesso decide antes do recurso.
        Map<String, String> ids = Map.of("id", "999999", "rid", "999999", "n", "1", "app", "server", "token",
                "link-que-nao-existe", "doc", "privacidade");

        // ── 2. Cada rota com sessões novas (logout e exclusão de conta não atrapalham as outras).
        List<String> wrong = new ArrayList<>();
        for (Route route : routes()) {
            sessions().forEach((caller, token) -> {
                int status = call(route, token, ids).statusCode();
                String problem = problem(route, caller, status);
                if (problem != null) {
                    wrong.add(route.key() + " como " + caller + ": " + problem);
                }
            });
        }

        // ── 3. Nada fora do lugar.
        assertThat(wrong).as("rotas com acesso errado").isEmpty();
    }

    /**
     * Um cliente nos recursos de outro.
     *
     * @throws IOException se o inventário não puder ser lido
     */
    @Test
    @DisplayName("T03: o pedido, a devolução e a avaliação de um cliente são 404 para outro cliente")
    void anotherCustomersResourcesAreNotFound() throws IOException {

        // ── 1. Pedido pago de B, com uma devolução e uma avaliação, cada uma com foto.
        Customer owner = newCustomer();
        Customer intruder = newCustomer();
        Map<String, String> ids = resourcesOf(owner);

        // ── 2. A chama cada rota de cliente que recebe id: 404, sem mexer em nada.
        List<String> wrong = new ArrayList<>();
        for (Route route : routes()) {
            if ("CUSTOMER".equals(route.access()) && route.path().contains("{")) {
                Map<String, String> vars = new LinkedHashMap<>(ids);
                if (route.path().startsWith("/api/cliente/feedback/")) {
                    vars.put("id", ids.get("feedback"));
                }
                int status = call(route, intruder.token(), vars).statusCode();
                if (status != NOT_404.getOrDefault(route.key(), 404)) {
                    wrong.add(route.key() + ": " + status);
                }
            }
        }
        assertThat(wrong).as("rotas que alcançam o recurso de outro cliente").isEmpty();

        // ── 3. Os ids eram de verdade: B vê as próprias fotos, e o pedido segue intacto.
        assertThat(as(owner.token()).get("/api/cliente/devolucoes/" + ids.get("rid") + "/fotos/1").statusCode())
                .isEqualTo(200);
        assertThat(as(owner.token()).get("/api/cliente/feedback/" + ids.get("feedback") + "/fotos/1").statusCode())
                .isEqualTo(200);
        long order = Long.parseLong(ids.get("order"));
        assertThat(jdbc.sql("SELECT cancelled_at IS NULL AND (SELECT count(*) FROM refunds r "
                + "WHERE r.shop_order_id = :id) = 1 FROM shop_orders WHERE id = :id").param("id", order)
                .query(Boolean.class).single()).isTrue();
    }

    /**
     * Sessões de cada tipo de visitante, novas.
     *
     * @return quem chama e o token (nulo = sem sessão)
     */
    private Map<String, String> sessions() {
        Map<String, String> sessions = new LinkedHashMap<>();
        sessions.put("visitante", null);
        sessions.put("dona", panelSession(OWNER));
        sessions.put("outra conta", panelSession(STRANGER));
        sessions.put("cliente", newCustomer().token());
        return sessions;
    }

    /**
     * O que está errado no status, para a coluna de acesso e quem chamou.
     *
     * @param route  rota
     * @param caller quem chamou
     * @param status status recebido
     * @return o problema, ou nulo
     */
    private static String problem(Route route, String caller, int status) {

        // ── 1. Erro do servidor nunca.
        if (status >= 500) {
            return "erro " + status;
        }

        // ── 2. Rotas de sessão: só quem pode passa; os outros recebem 401.
        boolean guarded = "OWNER".equals(route.access()) || "CUSTOMER".equals(route.access());
        boolean allowed = "OWNER".equals(route.access()) ? "dona".equals(caller) : "cliente".equals(caller);
        if (guarded && allowed && status == 401) {
            return "recusada (401) para quem pode";
        }
        if (guarded && !allowed && status != 401) {
            return "esperava 401, veio " + status;
        }
        return null;
    }

    /**
     * Chama a rota.
     *
     * @param route rota
     * @param token sessão (nulo = sem sessão)
     * @param ids   valor de cada variável do caminho
     * @return a resposta
     */
    private Response call(Route route, String token, Map<String, String> ids) {
        RequestSpecification request = request(route, token);
        String path = route.path();
        for (Map.Entry<String, String> id : ids.entrySet()) {
            path = path.replace("{" + id.getKey() + "}", id.getValue());
        }
        return request.urlEncodingEnabled(false).request(route.method(), path);
    }

    /**
     * Requisição com o corpo que a rota espera: formulário com foto na devolução, JSON nas outras.
     *
     * @param route rota
     * @param token sessão (nulo = sem sessão)
     * @return a requisição
     */
    private RequestSpecification request(Route route, String token) {
        if (!"POST".equals(route.method()) || !route.path().endsWith("/devolucao")) {
            RequestSpecification json = token == null ? api() : as(token);
            return "GET".equals(route.method()) ? json : json.body(BODY);
        }
        RequestSpecification form = RestAssured.given().port(port).multiPart("reason_code", "avariado")
                .multiPart("description", "Chegou todo amassado e derretido, veja a foto.")
                .multiPart("items", "[{\"id\":\"1\",\"qty\":1}]").multiPart("fotos", "a.png", PNG, "image/png");
        return token == null ? form : form.header("Authorization", "Bearer " + token);
    }

    /**
     * Pedido pago do cliente, com devolução e avaliação com fotos.
     *
     * @param customer cliente
     * @return {@code id}, {@code rid}, {@code n}, {@code order} e {@code feedback}
     */
    private Map<String, String> resourcesOf(Customer customer) {

        // ── 1. Pedido pago.
        long order = jdbc.sql("""
                INSERT INTO shop_orders (customer_id, owner_id, contact_name, phone, fulfillment, scheduled_for,
                                         time_window, payment_method, items, total, paid_at, public_code)
                VALUES (:c, (SELECT id FROM users WHERE username = :u), 'Cliente B', '11912345678', 'retirada',
                        current_date + 2, 'tarde', 'pix', '[]', 19, now(),
                        (SELECT COALESCE(max(public_code), 10000) + 1 FROM shop_orders))
                RETURNING id""").param("c", customer.id()).param("u", OWNER).query(Long.class).single();

        // ── 2. Devolução com foto.
        long refund = jdbc.sql("""
                INSERT INTO refunds (shop_order_id, owner_id, customer_id, kind, status, requested_amount)
                SELECT id, owner_id, customer_id, 'solicitado', 'solicitado', 5 FROM shop_orders WHERE id = :id
                RETURNING id""").param("id", order).query(Long.class).single();
        jdbc.sql("INSERT INTO refund_photos (refund_id, idx, content_type, data, size) "
                + "VALUES (:r, 1, 'image/png', '\\x89504e47', 4)").param("r", refund).update();

        // ── 3. Avaliação com foto.
        long feedback = jdbc.sql("INSERT INTO feedback (owner_id, customer_id, rating) "
                + "VALUES ((SELECT id FROM users WHERE username = :u), :c, 5) RETURNING id")
                .param("u", OWNER).param("c", customer.id()).query(Long.class).single();
        jdbc.sql("INSERT INTO feedback_photos (feedback_id, idx, content_type, data, size) "
                + "VALUES (:f, 1, 'image/png', '\\x89504e47', 4)").param("f", feedback).update();
        return Map.of("id", String.valueOf(order), "rid", String.valueOf(refund), "n", "1", "order",
                String.valueOf(order), "feedback", String.valueOf(feedback));
    }

    /**
     * Rotas migradas do inventário, com as que apagam a sessão no fim.
     *
     * @return as rotas
     * @throws IOException se o inventário não puder ser lido
     */
    private static List<Route> routes() throws IOException {

        // ── 1. Lê o inventário.
        String text;
        try (InputStream in = AccessMatrixIT.class.getResourceAsStream("/expected-routes.txt")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // ── 2. Linhas "MÉTODO caminho ACESSO", sem comentários nem pendentes.
        List<Route> routes = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 3 && !line.startsWith("#")) {
                routes.add(new Route(parts[0], parts[1], parts[2]));
            }
        }
        routes.sort(Comparator.comparingInt(route -> LAST.indexOf(route.key())));
        return routes;
    }

    /**
     * Uma linha do inventário.
     *
     * @param method método
     * @param path   caminho com variáveis
     * @param access acesso
     */
    private record Route(String method, String path, String access) {

        /**
         * Método e caminho.
         *
         * @return {@code MÉTODO caminho}
         */
        String key() {
            return method + " " + path;
        }
    }
}
