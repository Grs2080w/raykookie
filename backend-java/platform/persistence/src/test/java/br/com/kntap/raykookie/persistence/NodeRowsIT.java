package br.com.kntap.raykookie.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cada tipo do Postgres vira o mesmo valor JSON que o node-postgres (com os conversores de lib/db.ts) devolvia.
 */
class NodeRowsIT extends PostgresTestBase {

    /**
     * Os tipos usados pelas rotas, lado a lado com o JSON que o Node produziria.
     */
    @Test
    @DisplayName("tipos do Postgres no formato do Node")
    void typesLikeNode() {

        // ── 1. Uma linha com cada tipo.
        Map<String, Object> row = jdbc().sql("""
                SELECT 12.50::numeric AS fracao, 100.00::numeric AS inteiro, 2.5::float8 AS f8, 7::int AS i4,
                       count(*) AS i8, '2026-10-31'::date AS dia,
                       '2026-10-31 23:30:00.123456-03'::timestamptz AS quando, '{"a":[1,2]}'::jsonb AS doc,
                       ARRAY['novo','pronto'] AS lista, ARRAY[1.5,2]::numeric[] AS numeros, NULL::text AS nada, true AS sim
                  FROM (VALUES (1)) v""").query(NodeRows.ROW).single();

        // ── 2. Serializado, fica igual ao JSON do Node.
        String json = JsonMapper.builder().build().writeValueAsString(row);
        assertThat(json).isEqualTo("{\"fracao\":12.5,\"inteiro\":100,\"f8\":2.5,\"i4\":7,\"i8\":\"1\","
                + "\"dia\":\"2026-10-31\",\"quando\":\"2026-11-01T02:30:00.123Z\",\"doc\":{\"a\":[1,2]},"
                + "\"lista\":[\"novo\",\"pronto\"],\"numeros\":[1.5,2],\"nada\":null,\"sim\":true}");
        assertThat(row.get("doc")).isInstanceOf(JsonNode.class);
        assertThat(row.get("lista")).isEqualTo(List.of("novo", "pronto"));
    }

    /**
     * Colunas SERIAL (o driver informa o tipo "serial") e BIGSERIAL, como o node-postgres. A tabela fica
     * num banco próprio: no banco principal ela deixaria o esquema "public" não vazio e o Flyway dos
     * testes {@code @SpringBootTest} que rodassem depois recusaria subir.
     */
    @Test
    @DisplayName("SERIAL vira número; BIGSERIAL vira texto")
    void serialColumns() {

        // ── 1. Uma linha numa tabela SERIAL/BIGSERIAL, fora do banco principal.
        JdbcClient jdbc = newDatabase("node_rows").jdbc();
        jdbc.sql("CREATE TABLE node_rows_serial (a SERIAL, b BIGSERIAL)").update();
        jdbc.sql("INSERT INTO node_rows_serial DEFAULT VALUES").update();

        // ── 2. Os tipos lidos são os do node-postgres.
        Map<String, Object> row = jdbc.sql("SELECT a, b FROM node_rows_serial LIMIT 1").query(NodeRows.ROW).single();
        assertThat(row.get("a")).isInstanceOf(Integer.class);
        assertThat(row.get("b")).isInstanceOf(String.class);
    }

    /**
     * Números de double como o JavaScript os escreve.
     */
    @Test
    @DisplayName("double inteiro sem casas")
    void doubles() {
        assertThat(NodeRows.number(100.0)).isEqualTo(100L);
        assertThat(NodeRows.number(0.1 + 0.2)).isEqualTo(0.30000000000000004);
        assertThat(NodeRows.number((java.math.BigDecimal) null)).isNull();
        assertThat(NodeRows.timestamp(null)).isNull();
    }
}
