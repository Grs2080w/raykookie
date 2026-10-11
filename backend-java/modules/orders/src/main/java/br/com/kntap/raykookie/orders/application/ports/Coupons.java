package br.com.kntap.raykookie.orders.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.orders.domain.CouponRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cupons de desconto da dona (tabela {@code coupons}).
 */
public interface Coupons {

    /**
     * Cupom pelo código (sem diferença de maiúsculas).
     *
     * @param owner dona
     * @param code  código normalizado
     * @return o cupom, ou vazio
     */
    Optional<CouponRules.Coupon> find(OwnerId owner, String code);

    /**
     * Cupons da dona, do mais novo para o mais antigo, com quantos pedidos (não cancelados) usaram cada um.
     *
     * @param owner dona
     * @return {@code id}, {@code code}, {@code percent}, {@code min_total}, {@code min_inclusive}, {@code active},
     *         {@code uses} e {@code created_at}
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Diz se o código já existe na loja.
     *
     * @param owner dona
     * @param code  código normalizado
     * @return verdadeiro se existe
     */
    boolean codeTaken(OwnerId owner, String code);

    /**
     * Cria um cupom.
     *
     * @param owner dona
     * @param form  dados
     * @return o id
     */
    long create(OwnerId owner, Form form);

    /**
     * Edita porcentagem, valor mínimo e se está ativo.
     *
     * @param owner dona
     * @param id    cupom
     * @param form  dados (o código não muda)
     * @return verdadeiro se o cupom era da dona
     */
    boolean update(OwnerId owner, long id, Form form);

    /**
     * Quantos pedidos usaram o cupom.
     *
     * @param owner dona
     * @param id    cupom
     * @return o total
     */
    int uses(OwnerId owner, long id);

    /**
     * Apaga um cupom.
     *
     * @param owner dona
     * @param id    cupom
     */
    void delete(OwnerId owner, long id);

    /**
     * Dados de um cupom.
     *
     * @param code         código
     * @param percent      desconto (%)
     * @param minTotal     valor mínimo (nulo = nenhum)
     * @param minInclusive "a partir de" (verdadeiro) ou "acima de" (falso)
     * @param active       se está ativo
     */
    record Form(String code, BigDecimal percent, BigDecimal minTotal, boolean minInclusive, boolean active) {
    }
}
