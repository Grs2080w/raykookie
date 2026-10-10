package br.com.kntap.raykookie.orders.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.kernel.Money;
import br.com.kntap.raykookie.storefront.api.Menu;
import br.com.kntap.raykookie.web.NodeInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Optional;

/**
 * Pedido da loja como o cliente o enviou, conferido ({@code parseShopOrder} de {@code backend/shared/shop.ts}): os
 * preços vêm sempre do cardápio do servidor.
 *
 * @param contactName   nome para contato
 * @param phone         telefone (só dígitos)
 * @param fulfillment   {@code retirada} ou {@code entrega}
 * @param address       endereço de entrega (vazio na retirada)
 * @param scheduledFor  data desejada
 * @param timeWindow    período do dia
 * @param paymentMethod {@code pix}, {@code cartao} ou {@code dinheiro}
 * @param changeFor     troco para (nulo = nenhum)
 * @param notes         observações (até 300)
 * @param items         itens, com o preço do cardápio
 * @param total         total
 * @param card          dados do cartão (nulo se não for cartão; nunca guardados)
 */
public record ShopOrderForm(String contactName, String phone, String fulfillment, Address address,
        LocalDate scheduledFor,
        String timeWindow, String paymentMethod, Money changeFor, String notes, List<Line> items, Money total,
        Card card) {

    /** Quantidade máxima de cada item. */
    public static final int MAX_QTY = 50;

    /** Parcelamento só a partir deste valor (reais). */
    public static final Money INSTALLMENTS_MIN = Money.ofCents(15_000);

    /** Parcelas máximas. */
    public static final int MAX_INSTALLMENTS = 12;

    /** Formas de pagamento e o nome que aparece na tela Pedidos. */
    public static final Map<String, String> PAYMENTS = Map.of("pix", "Pix", "cartao", "Cartão", "dinheiro", "Dinheiro");

    /**
     * Confere o formulário.
     *
     * @param body  corpo JSON
     * @param today hoje no fuso da loja
     * @param menu  itens do cardápio
     * @return o pedido
     * @throws InvalidInputException com a mensagem do primeiro problema
     */
    public static ShopOrderForm from(Map<String, Object> body, LocalDate today, List<Menu.Item> menu) {

        // ── 1. Itens e total pelo cardápio.
        List<Line> items = items(body.get("items"), menu);
        Money total = items.stream().map(line -> line.unitPrice().times(line.qty())).reduce(Money.ZERO, Money::plus);

        // ── 2. Contato e forma de receber.
        String contact = NodeInput.cut(NodeInput.text(body.get("contact_name")), 80);
        require(contact.length() >= 2, "Informe seu nome.");
        String phone = digits(body.get("phone"), 11);
        require(phone.length() >= 10,
                "Informe um telefone com DDD. Só usamos se precisarmos falar com você sobre o pedido.");
        String fulfillment = NodeInput.text(body.get("fulfillment"));
        require("retirada".equals(fulfillment) || "entrega".equals(fulfillment),
                "Escolha se você vai retirar ou receber em casa.");
        Address address = "entrega".equals(fulfillment) ? Address.delivery(body) : Address.EMPTY;

        // ── 3. Data, período e pagamento.
        LocalDate scheduled = scheduledFor(body.get("scheduled_for"), today);
        String window = NodeInput.text(body.get("time_window"));
        require(List.of("manha", "tarde", "noite").contains(window), "Escolha o período do dia.");
        String payment = NodeInput.text(body.get("payment_method"));
        require(PAYMENTS.containsKey(payment), "Escolha a forma de pagamento.");
        Money change = "dinheiro".equals(payment) ? changeFor(body.get("change_for"), total) : null;
        Card card = "cartao".equals(payment) ? Card.from(body, total) : null;
        return new ShopOrderForm(contact, phone, fulfillment, address, scheduled, window, payment, change,
                NodeInput.cut(NodeInput.text(body.get("notes")), 300), items, total, card);
    }

    /**
     * Itens do carrinho: todos no cardápio, quantidade inteira de 1 a 50, repetidos juntos.
     *
     * @param raw  valor de {@code items}
     * @param menu cardápio
     * @return os itens com o preço do cardápio, na ordem da primeira aparição
     */
    private static List<Line> items(Object raw, List<Menu.Item> menu) {

        // ── 1. Junta as quantidades por item, conferindo cada linha.
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Object element : raw instanceof List<?> list ? list : List.of()) {
            Map<?, ?> line = element instanceof Map<?, ?> map ? map : Map.of();
            String id = NodeInput.text(line.get("id"));
            Optional<Long> qty = NodeInput.integer(line.get("qty"));
            require(menu.stream().anyMatch(item -> item.id().equals(id)),
                    "Um dos itens escolhidos não está mais no cardápio.");
            require(qty.isPresent() && qty.get() >= 1 && qty.get() <= MAX_QTY,
                    "A quantidade de cada item vai de 1 a " + MAX_QTY + ".");
            merged.merge(id, qty.get(), Long::sum);
        }
        require(!merged.isEmpty(), "Escolha pelo menos um item do cardápio.");

        // ── 2. Nome e preço do cardápio do servidor.
        List<Line> lines = new ArrayList<>();
        merged.forEach((id, qty) -> {
            Menu.Item item = menu.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
            lines.add(new Line(id, item.name(), Math.min(qty, MAX_QTY), item.money()));
        });
        return lines;
    }

    /**
     * Data desejada: de amanhã até 6 meses.
     *
     * @param raw   valor
     * @param today hoje
     * @return a data
     */
    private static LocalDate scheduledFor(Object raw, LocalDate today) {
        LocalDate date = IsoDates.parse(raw).orElseThrow(() -> new InvalidInputException("Escolha a data desejada."));
        require(!date.isBefore(today.plusDays(1)),
                "Fazemos tudo sob encomenda: escolha uma data com pelo menos 1 dia de antecedência.");
        require(!date.isAfter(today.plusDays(180)), "Escolha uma data nos próximos 6 meses.");
        return date;
    }

    /**
     * Troco: opcional; se informado, entre o total e R$ 100.000.
     *
     * @param raw   valor digitado
     * @param total total do pedido
     * @return o troco, ou nulo
     */
    private static Money changeFor(Object raw, Money total) {
        String text = NodeInput.text(raw);
        if (text.isEmpty()) {
            return null;
        }
        BigDecimal value = NodeInput.localeDecimal(text).orElse(null);
        require(value != null && value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100_000)) <= 0,
                "O valor do troco não é válido.");
        require(value.compareTo(total.toDecimal()) >= 0,
                "O troco precisa ser para um valor maior ou igual ao total do pedido.");
        return NodeInput.localeMoney(text).orElseThrow();
    }

    /**
     * Só os dígitos, até um tamanho ({@code onlyDigits}).
     *
     * @param value valor
     * @param max   tamanho máximo
     * @return os dígitos
     */
    static String digits(Object value, int max) {
        return NodeInput.cut(NodeInput.text(value).replaceAll("\\D", ""), max);
    }

    /**
     * Exige uma condição.
     *
     * @param ok      condição
     * @param message mensagem se falhar
     */
    static void require(boolean ok, String message) {
        if (!ok) {
            throw new InvalidInputException(message);
        }
    }

    /**
     * Um item.
     *
     * @param id        id do item do cardápio
     * @param name      nome
     * @param qty       quantidade
     * @param unitPrice preço do cardápio
     */
    public record Line(String id, String name, long qty, Money unitPrice) {
    }

    /**
     * Endereço de entrega.
     *
     * @param cep        CEP (8 dígitos)
     * @param street     rua
     * @param number     número
     * @param complement complemento
     * @param district   bairro
     * @param city       cidade
     */
    public record Address(String cep, String street, String number, String complement, String district, String city) {

        /** Retirada: sem endereço. */
        public static final Address EMPTY = new Address("", "", "", "", "", "");

        /**
         * Endereço de entrega completo.
         *
         * @param body corpo
         * @return o endereço
         */
        static Address delivery(Map<String, Object> body) {

            // ── 1. Campos com os tamanhos do Node.
            String cep = digits(body.get("cep"), 8);
            String street = NodeInput.cut(NodeInput.text(body.get("street")), 100);
            String number = NodeInput.cut(NodeInput.text(body.get("number")), 10);
            String district = NodeInput.cut(NodeInput.text(body.get("district")), 60);
            String city = NodeInput.cut(NodeInput.text(body.get("city")), 60);

            // ── 2. Obrigatórios, na ordem do Node.
            require(cep.length() == 8, "Informe o CEP com 8 números.");
            require(!street.isEmpty(), "Informe a rua do endereço de entrega.");
            require(!number.isEmpty(), "Informe o número (ou S/N, se não tiver).");
            require(!district.isEmpty(), "Informe o bairro.");
            require(!city.isEmpty(), "Informe a cidade.");
            return new Address(cep, street, number, NodeInput.cut(NodeInput.text(body.get("complement")), 60),
                    district, city);
        }
    }

    /**
     * Dados do cartão, tokenizado no site/app (nunca o número).
     *
     * @param token        token do Mercado Pago
     * @param methodId     bandeira
     * @param issuerId     emissor ({@code card_issuer_id}, opcional: as versões antigas do app não mandam)
     * @param cpf          CPF só com dígitos
     * @param installments parcelas
     * @param deviceId     device ID do antifraude do Mercado Pago ({@code device_id}, opcional)
     */
    public record Card(String token, String methodId, String issuerId, String cpf, int installments,
            String deviceId) {

        /** Formato do device ID do Mercado Pago (letras, números e {@code :._-}). */
        private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

        /**
         * Device ID do antifraude do Mercado Pago, se veio num formato aceitável.
         *
         * @param raw valor do corpo
         * @return o device ID, ou nulo
         */
        public static String deviceId(Object raw) {
            String text = NodeInput.text(raw);
            return DEVICE_ID.matcher(text).matches() ? text : null;
        }

        /**
         * Confere os dados do cartão.
         *
         * @param body  corpo
         * @param total total do pedido
         * @return o cartão
         */
        static Card from(Map<String, Object> body, Money total) {

            // ── 1. Token e bandeira.
            String token = NodeInput.text(body.get("card_token"));
            require(!token.isEmpty(), "Preencha os dados do cartão.");
            String method = NodeInput.text(body.get("card_payment_method_id"));
            require(!method.isEmpty(), "Não reconhecemos a bandeira desse cartão.");

            // ── 2. CPF e parcelas.
            require(Cpf.isValid(body.get("cpf")),
                    "Informe um CPF válido (exigido pelo Mercado Pago para pagar com cartão).");
            int installments = installments(body.get("installments"));
            require(installments <= 1 || INSTALLMENTS_MIN.isLessThan(total),
                    "Parcelamento só para pedidos acima de R$ 150.");
            String issuer = NodeInput.text(body.get("card_issuer_id"));
            return new Card(token, method, issuer.isEmpty() ? null : issuer, Cpf.clean(body.get("cpf")), installments,
                    deviceId(body.get("device_id")));
        }

        /**
         * Parcelas de 1 a 12.
         *
         * @param raw valor
         * @return as parcelas
         */
        public static int installments(Object raw) {
            Optional<Long> value = NodeInput.integer(raw);
            require(value.isPresent() && value.get() >= 1 && value.get() <= MAX_INSTALLMENTS,
                    "Escolha uma quantidade de parcelas válida.");
            return value.get().intValue();
        }
    }
}
