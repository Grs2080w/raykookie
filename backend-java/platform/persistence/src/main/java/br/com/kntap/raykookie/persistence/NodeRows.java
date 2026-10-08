package br.com.kntap.raykookie.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Linhas do banco no mesmo JSON que o Node devolvia: o {@code node-postgres} com os conversores de
 * {@code backend/lib/db.ts}. Serve às rotas de consulta que o Node montava direto do SQL, para o site e os
 * apps receberem exatamente os mesmos campos e tipos.
 *
 * <ul>
 *   <li>{@code NUMERIC}, {@code float4/8} → número JS (inteiro sem casas: {@code 100}, não {@code 100.0});</li>
 *   <li>{@code int2/int4} → número; {@code int8} → <b>texto</b> (padrão do node-postgres, ex.:
 *       {@code count(*)});</li>
 *   <li>{@code DATE} → {@code "AAAA-MM-DD"}; {@code TIMESTAMPTZ} → ISO em UTC com milissegundos
 *       ({@code Date.toISOString()});</li>
 *   <li>{@code JSON/JSONB} → objeto; arrays → listas; o resto → texto ou booleano.</li>
 * </ul>
 */
public final class NodeRows {

    /** O mapeador de linha, na ordem das colunas do SELECT. */
    public static final RowMapper<Map<String, Object>> ROW = NodeRows::row;

    /** Formato do {@code Date.toISOString()} do JavaScript. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** Leitor das colunas JSON. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Sem instâncias.
     */
    private NodeRows() {
    }

    /**
     * Uma linha como mapa ordenado.
     *
     * @param rs     resultado
     * @param rowNum número da linha
     * @return coluna → valor no formato do Node
     * @throws SQLException se a leitura falhar
     */
    public static Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), value(rs, i, meta.getColumnTypeName(i)));
        }
        return row;
    }

    /**
     * Número como o JavaScript o escreve: inteiro sem casas, fração como {@code double}.
     *
     * @param number o número (nulo = nulo)
     * @return {@link Long} se for inteiro, senão {@link Double}
     */
    public static Object number(BigDecimal number) {
        if (number == null) {
            return null;
        }
        BigDecimal stripped = number.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
            return stripped.longValueExact();
        }
        return number.doubleValue();
    }

    /**
     * Número {@code double} como o JavaScript o escreve.
     *
     * @param number o número
     * @return {@link Long} se for inteiro, senão o próprio {@code double}
     */
    public static Object number(double number) {
        if (number == Math.rint(number) && !Double.isInfinite(number) && Math.abs(number) < 1e15) {
            return (long) number;
        }
        return number;
    }

    /**
     * Instante no formato {@code Date.toISOString()}.
     *
     * @param instant o instante (nulo = nulo)
     * @return {@code AAAA-MM-DDTHH:MM:SS.mmmZ}
     */
    public static String timestamp(Instant instant) {
        return instant == null ? null : ISO.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * Valor de uma coluna, pelo tipo do Postgres.
     *
     * @param rs       resultado
     * @param index    coluna (a partir de 1)
     * @param typeName tipo no Postgres ({@code numeric}, {@code _int4}…)
     * @return o valor no formato do Node
     * @throws SQLException se a leitura falhar
     */
    private static Object value(ResultSet rs, int index, String typeName) throws SQLException {
        if (rs.getObject(index) == null) {
            return null;
        }
        return switch (typeName) {
            case "numeric" -> number(rs.getBigDecimal(index));
            case "float4", "float8" -> number(rs.getDouble(index));
            case "int2", "int4", "serial", "smallserial" -> rs.getInt(index);
            case "int8", "bigserial", "text", "varchar", "bpchar", "name", "uuid" -> rs.getString(index);
            case "bool" -> rs.getBoolean(index);
            case "date" -> rs.getString(index);
            case "timestamptz", "timestamp" -> timestamp(rs.getObject(index, OffsetDateTime.class).toInstant());
            case "json", "jsonb" -> JSON.readTree(rs.getString(index));
            default -> arrayOrText(rs, index, typeName);
        };
    }

    /**
     * Array ({@code _tipo}) como lista; qualquer outro tipo como texto.
     *
     * @param rs       resultado
     * @param index    coluna
     * @param typeName tipo no Postgres
     * @return a lista ou o texto
     * @throws SQLException se a leitura falhar
     */
    private static Object arrayOrText(ResultSet rs, int index, String typeName) throws SQLException {
        if (typeName.startsWith("_")) {
            return array(rs.getArray(index), typeName.substring(1));
        }
        return rs.getString(index);
    }

    /**
     * Array do Postgres como lista, elemento a elemento pelas mesmas regras.
     *
     * @param array    o array
     * @param elemType tipo dos elementos
     * @return a lista
     * @throws SQLException se a leitura falhar
     */
    private static List<Object> array(Array array, String elemType) throws SQLException {
        List<Object> list = new ArrayList<>();
        try (ResultSet elements = array.getResultSet()) {
            while (elements.next()) {
                list.add(elements.getObject(2) == null ? null : value(elements, 2, elemType));
            }
        }
        return list;
    }
}
