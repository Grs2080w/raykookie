package br.com.kntap.raykookie.engagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A lista fechada de eventos: o que entra e o que é descartado.
 */
class EngagementCatalogTest {

    /** Agora dos testes. */
    private static final Instant NOW = Instant.parse("2026-10-13T15:00:00Z");

    /**
     * Evento de tela.
     *
     * @param route rota
     * @return o evento
     */
    private static Map<String, Object> view(Object route) {
        Map<String, Object> event = new HashMap<>();
        event.put("name", "screen_view");
        event.put("at", NOW.toEpochMilli());
        event.put("route", route);
        return event;
    }

    /**
     * Lote com eventos.
     *
     * @param events eventos
     * @return o corpo
     */
    private static Map<String, Object> body(List<?> events) {
        return Map.of("session_id", UUID.randomUUID().toString(), "platform", "site", "app_version", "1.2.3",
                "events", events);
    }

    /**
     * Rota sem parâmetros e com id trocado.
     */
    @Test
    @DisplayName("a rota perde a consulta e o id numérico vira :id; o que não é caminho simples é descartado")
    void routesAreNormalised() {
        assertThat(EngagementCatalog.route("/cliente/pedidos/123?token=abc#x")).isEqualTo("/cliente/pedidos/:id");
        assertThat(EngagementCatalog.route("/cardapio/")).isEqualTo("/cardapio");
        assertThat(EngagementCatalog.route("/")).isEqualTo("/");
        assertThat(EngagementCatalog.route("cardapio")).isEmpty();
        assertThat(EngagementCatalog.route("/a b")).isEmpty();
        assertThat(EngagementCatalog.route("/" + "a".repeat(100))).isEmpty();
        assertThat(EngagementCatalog.route(42)).isEmpty();
    }

    /**
     * Eventos bons passam; os ruins somem sem derrubar o lote.
     */
    @Test
    @DisplayName("evento desconhecido, fora da lista, sem campo obrigatório ou fora da hora é descartado")
    void badEventsAreDropped() {
        Map<String, Object> click = Map.of("name", "menu_item_click", "at", NOW.toEpochMilli(), "item_id", "12",
                "detail", "cardapio");
        Map<String, Object> coupon = Map.of("name", "coupon_attempt", "at", NOW.toEpochMilli(), "detail", "NATAL20");
        Map<String, Object> unknown = Map.of("name", "keylog", "at", NOW.toEpochMilli());
        Map<String, Object> noItem = Map.of("name", "cart_add", "at", NOW.toEpochMilli(), "qty", 1);
        Map<String, Object> badQty = Map.of("name", "cart_add", "at", NOW.toEpochMilli(), "item_id", "combo-3",
                "qty", 500);
        Map<String, Object> goodCart = Map.of("name", "cart_add", "at", NOW.toEpochMilli(), "item_id", "combo-3",
                "qty", 2);
        Map<String, Object> old = Map.of("name", "order_placed", "at", NOW.minusSeconds(3 * 86_400).toEpochMilli(),
                "detail", "pix");
        Map<String, Object> noTime = Map.of("name", "order_placed", "detail", "pix");

        EngagementBatch batch = EngagementCatalog.batch(body(List.of(view("/cardapio"), click, coupon, unknown,
                noItem, badQty, goodCart, old, noTime, "texto")), NOW);

        assertThat(batch.events()).extracting(EngagementEvent::name)
                .containsExactly("screen_view", "menu_item_click", "cart_add");
        assertThat(batch.events().get(2).itemId()).isEqualTo("combo-3");
        assertThat(batch.events().get(2).qty()).isEqualTo(2);
        assertThat(batch.platform()).isEqualTo("site");
        assertThat(batch.appVersion()).isEqualTo("1.2.3");
    }

    /**
     * O código de cupom digitado nunca entra.
     */
    @Test
    @DisplayName("o código de cupom digitado não é aceito como detalhe")
    void couponCodeNeverEnters() {
        Map<String, Object> coupon = Map.of("name", "coupon_attempt", "at", NOW.toEpochMilli(), "detail", "NATAL20");
        assertThat(EngagementCatalog.batch(body(List.of(coupon, view("/"))), NOW).events()).hasSize(1);
    }

    /**
     * Formato do lote errado: recusa.
     */
    @Test
    @DisplayName("sessão, plataforma ou lista de eventos inválidos recusam o lote")
    void malformedBatchesAreRefused() {
        assertThatThrownBy(() -> EngagementCatalog.batch(Map.of("session_id", "x", "platform", "site", "events",
                List.of(view("/"))), NOW)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> EngagementCatalog.batch(Map.of("session_id", UUID.randomUUID().toString(),
                "platform", "tv", "events", List.of(view("/"))), NOW)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> EngagementCatalog.batch(body(List.of()), NOW))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> EngagementCatalog.batch(Map.of("session_id", UUID.randomUUID().toString(),
                "platform", "site"), NOW)).isInstanceOf(InvalidInputException.class);
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i <= EngagementCatalog.MAX_BATCH; i++) {
            many.add(view("/"));
        }
        assertThatThrownBy(() -> EngagementCatalog.batch(body(many), NOW)).isInstanceOf(InvalidInputException.class);
    }

    /**
     * Versão estranha vira vazia.
     */
    @Test
    @DisplayName("versão do app fora do formato fica vazia e a lista de nomes está completa")
    void oddVersionIsBlanked() {
        Map<String, Object> odd = new HashMap<>(body(List.of(view("/"))));
        odd.put("app_version", "<script>");
        assertThat(EngagementCatalog.batch(odd, NOW).appVersion()).isEmpty();
        assertThat(EngagementCatalog.names()).contains("screen_view", "menu_item_click", "order_placed")
                .hasSize(11);
    }
}
