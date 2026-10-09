package br.com.kntap.raykookie.orders;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.application.ManualOrderUseCases;
import br.com.kntap.raykookie.orders.application.ShopOrderUseCases;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Numeração da tela Pedidos sob concorrência (T06, G10): pedidos à mão e da loja para a mesma dona ao mesmo tempo
 * saem com números seguidos, sem repetir e sem erro.
 */
@SpringBootTest(properties = {"rayka.shop-owner=dona", "rayka.jobs-enabled=false",
    "spring.flyway.locations=classpath:db/migration/production,classpath:db/migration/platform,"
            + "classpath:db/testdata"})
class OrderNumberConcurrencyIT extends PostgresTestBase {

    /** Pedidos por tipo. */
    private static final int EACH = 20;

    /** Banco. */
    @Autowired
    JdbcClient jdbc;

    /** Pedidos à mão. */
    @Autowired
    ManualOrderUseCases manual;

    /** Pedidos da loja. */
    @Autowired
    ShopOrderUseCases shop;

    /**
     * 20 pedidos à mão e 20 da loja ao mesmo tempo.
     *
     * @throws Exception se uma tarefa falhar
     */
    @Test
    @DisplayName("20 pedidos à mão + 20 da loja ao mesmo tempo: 40 números seguidos, sem repetição")
    void concurrentNumbers() throws Exception {

        // ── 1. Dona, receita no cardápio e 20 clientes.
        OwnerId owner = new OwnerId(jdbc.sql("SELECT id FROM users WHERE username = 'dona'").query(Long.class).single());
        long recipe = jdbc.sql("INSERT INTO recipes (user_id, name, sale_price) VALUES (:o, :n, 12) RETURNING id")
                .param("o", owner.value()).param("n", "Bolo concorrente " + UUID.randomUUID()).query(Long.class)
                .single();
        long flour = jdbc.sql("""
                INSERT INTO ingredients (user_id, name, unit, package_size, package_cost)
                VALUES (:o, :n, 'g', 1000, 10) RETURNING id""").param("o", owner.value()).param("n", "F " + recipe)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO recipe_items (recipe_id, ingredient_id, qty) VALUES (:r, :i, 50)").param("r", recipe)
                .param("i", flour).update();
        long before = jdbc.sql("SELECT COALESCE(MAX(number), 0) FROM orders WHERE user_id = :o")
                .param("o", owner.value()).query(Long.class).single();

        // ── 2. As 40 tarefas, todas soltas juntas.
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < EACH; i++) {
            tasks.add(() -> manual.create(owner, Map.of("customer", "Ana", "product", "Bolo", "qty", 1,
                    "unit_price", 10)));
            CustomerId customer = new CustomerId(jdbc.sql("INSERT INTO customers (email, name) VALUES (:e, 'C') "
                    + "RETURNING id").param("e", "conc-" + UUID.randomUUID() + "@x.com").query(Long.class).single());
            tasks.add(() -> shop.create(customer, shopOrder(recipe), "203.0.113.9"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(EACH * 2);
        try {
            for (Future<Object> done : pool.invokeAll(tasks)) {
                done.get();
            }
        } finally {
            pool.shutdown();
        }

        // ── 3. Números seguidos, sem repetição.
        List<Long> numbers = jdbc.sql("SELECT number FROM orders WHERE user_id = :o AND number > :b ORDER BY number")
                .param("o", owner.value()).param("b", before).query(Long.class).list();
        assertThat(numbers).containsExactlyElementsOf(LongStream.rangeClosed(before + 1, before + EACH * 2).boxed()
                .toList());
    }

    /**
     * Pedido em dinheiro da receita.
     *
     * @param recipe receita
     * @return o corpo
     */
    private static Map<String, Object> shopOrder(long recipe) {
        return Map.of("items", List.of(Map.of("id", String.valueOf(recipe), "qty", 1)), "contact_name", "Cliente",
                "phone", "11912345678", "fulfillment", "retirada", "scheduled_for",
                LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(3).toString(), "time_window", "manha",
                "payment_method", "dinheiro");
    }
}
