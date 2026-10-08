package br.com.kntap.raykookie.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.storefront.api.Menu;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code parseShopOrder}: os casos de {@code backend/tests/shop.test.ts}.
 */
class ShopOrderFormTest {

    /** Cardápio dos testes. */
    private static final List<Menu.Item> MENU = List.of(
            new Menu.Item("1", "Cookie tradicional", 9, "Cookies e biscoitos", 0, null, List.of()),
            new Menu.Item("2", "Caixa com 6 brownies", 62, "Brigadeiros e docinhos", 0, null, List.of()),
            new Menu.Item("3", "Bolo de chocolate", 98, "Bolos", 0, null, List.of()));

    /** CPF válido de teste. */
    private static final String CPF = "529.982.247-25";

    /** Hoje. */
    private static final LocalDate TODAY = LocalDate.parse("2026-10-01");

    /**
     * Pedido base: 4 cookies e 1 caixa de brownies (R$ 98) para retirar.
     *
     * @return o corpo
     */
    private static Map<String, Object> base() {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("id", "1", "qty", 4), Map.of("id", "2", "qty", 1)));
        body.put("contact_name", "Maria Souza");
        body.put("phone", "(11) 91234-5678");
        body.put("fulfillment", "retirada");
        body.put("scheduled_for", "2026-10-05");
        body.put("time_window", "tarde");
        body.put("payment_method", "pix");
        body.put("notes", "");
        return body;
    }

    /**
     * Pedido base com cartão.
     *
     * @return o corpo
     */
    private static Map<String, Object> card() {
        Map<String, Object> body = base();
        body.putAll(Map.of("payment_method", "cartao", "card_token", "tok_123", "card_payment_method_id", "master",
                "cpf", CPF, "installments", 1));
        return body;
    }

    /**
     * Confere o corpo.
     *
     * @param body corpo
     * @return o pedido
     */
    private static ShopOrderForm parse(Map<String, Object> body) {
        return ShopOrderForm.from(body, TODAY, MENU);
    }

    /**
     * Mensagem do erro.
     *
     * @param body corpo
     * @return a mensagem
     */
    private static String error(Map<String, Object> body) {
        try {
            parse(body);
            return null;
        } catch (InvalidInputException e) {
            return e.getMessage();
        }
    }

    /**
     * Corpo base com um campo trocado.
     *
     * @param key   campo
     * @param value valor
     * @return o corpo
     */
    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> body = base();
        body.put(key, value);
        return body;
    }

    /**
     * Preço do servidor e itens repetidos.
     */
    @Test
    @DisplayName("total pelo cardápio do servidor (preço do corpo ignorado) e itens repetidos juntos")
    void serverPrices() {
        ShopOrderForm form = parse(with("items", List.of(Map.of("id", "1", "qty", 4, "unit_price", 0.01))));
        assertThat(form.total()).isEqualTo(Money.ofCents(3600));
        assertThat(form.phone()).isEqualTo("11912345678");
        form = parse(with("items", List.of(Map.of("id", "1", "qty", 2), Map.of("id", "1", "qty", 3))));
        assertThat(form.items()).containsExactly(new ShopOrderForm.Line("1", "Cookie tradicional", 5,
                Money.ofCents(900)));
    }

    /**
     * Itens inválidos.
     */
    @Test
    @DisplayName("recusa item inexistente, quantidade inválida e carrinho vazio")
    void invalidItems() {
        assertThat(error(with("items", List.of(Map.of("id", "x", "qty", 1))))).contains("cardápio");
        assertThat(error(with("items", List.of(Map.of("id", "1", "qty", 0))))).contains("quantidade");
        assertThat(error(with("items", List.of(Map.of("id", "1", "qty", 1.5))))).contains("quantidade");
        assertThat(error(with("items", List.of()))).contains("pelo menos um item");
    }

    /**
     * Data.
     */
    @Test
    @DisplayName("exige antecedência mínima e data real")
    void date() {
        assertThat(error(with("scheduled_for", "2026-10-01"))).contains("antecedência");
        assertThat(error(with("scheduled_for", "2026-10-02"))).isNull();
        assertThat(error(with("scheduled_for", "2026-02-31"))).containsIgnoringCase("data");
        assertThat(error(with("scheduled_for", "2027-04-01"))).isEqualTo("Escolha uma data nos próximos 6 meses.");
    }

    /**
     * Endereço.
     */
    @Test
    @DisplayName("entrega exige endereço completo; retirada descarta o endereço")
    void address() {
        assertThat(error(with("fulfillment", "entrega"))).contains("CEP");
        Map<String, Object> address = Map.of("cep", "01310-100", "street", "Av. Paulista", "number", "1000",
                "district", "Bela Vista", "city", "São Paulo");
        Map<String, Object> delivery = with("fulfillment", "entrega");
        delivery.putAll(address);
        assertThat(parse(delivery).address().cep()).isEqualTo("01310100");
        Map<String, Object> pickup = base();
        pickup.putAll(address);
        assertThat(parse(pickup).address().street()).isEmpty();
    }

    /**
     * Telefone, pagamento e troco.
     */
    @Test
    @DisplayName("valida telefone, pagamento e troco")
    void paymentAndChange() {
        assertThat(error(with("phone", "123"))).contains("telefone");
        assertThat(error(with("payment_method", "fiado"))).contains("pagamento");
        Map<String, Object> cash = with("payment_method", "dinheiro");
        cash.put("change_for", "50");
        assertThat(error(cash)).contains("troco");
        cash.put("change_for", "100,00");
        assertThat(parse(cash).changeFor()).isEqualTo(Money.ofCents(10_000));
        assertThat(parse(with("change_for", "100")).changeFor()).isNull();
    }

    /**
     * Limpeza e tamanhos.
     */
    @Test
    @DisplayName("limpa caracteres de controle e limita tamanhos")
    void cleaning() {
        Map<String, Object> body = with("contact_name", "Ma\u0000ria");
        body.put("notes", "x".repeat(500));
        ShopOrderForm form = parse(body);
        assertThat(form.contactName()).isEqualTo("Maria");
        assertThat(form.notes()).hasSize(300);
    }

    /**
     * Cartão.
     */
    @Test
    @DisplayName("cartão: token, bandeira, CPF válido e parcelas só acima de R$ 150")
    void cardRules() {

        // ── 1. Dados obrigatórios.
        Map<String, Object> body = card();
        body.put("card_token", "");
        assertThat(error(body)).contains("dados do cartão");
        body = card();
        body.put("card_payment_method_id", "");
        assertThat(error(body)).contains("bandeira");
        body = card();
        body.put("cpf", "111.111.111-11");
        assertThat(error(body)).contains("CPF");

        // ── 2. Parcelas.
        body = card();
        body.put("installments", 2);
        assertThat(error(body)).contains("150");
        body.put("installments", 0);
        assertThat(error(body)).contains("parcelas");
        body.put("installments", 13);
        assertThat(error(body)).contains("parcelas");
        body.put("items", List.of(Map.of("id", "3", "qty", 2)));
        body.put("installments", 3);
        ShopOrderForm form = parse(body);
        assertThat(form.card()).isEqualTo(new ShopOrderForm.Card("tok_123", "master", "52998224725", 3));
    }

    /**
     * Sem corpo.
     */
    @Test
    @DisplayName("corpo vazio: pede pelo menos um item")
    void emptyBody() {
        assertThatThrownBy(() -> parse(Map.of())).hasMessage("Escolha pelo menos um item do cardápio.");
    }
}
