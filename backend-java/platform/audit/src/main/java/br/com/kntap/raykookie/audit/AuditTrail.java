package br.com.kntap.raykookie.audit;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Map;

/**
 * Ponto único para gravar o histórico de auditoria. Todo caso de uso que muda estoque, receita, preço, cupom ou pedido
 * chama {@link #record} <b>dentro da mesma transação</b> da mudança: se a ação falhar, nada é registrado; se o registro
 * falhar, a exceção sobe e a ação inteira é desfeita (nunca passa em silêncio). Quem chama não vê a requisição: o
 * usuário, o papel, a rota e o IP são anotados pelo adaptador.
 */
public interface AuditTrail {

    /**
     * Grava uma linha no histórico.
     *
     * @param owner quem fez (a conta do painel da requisição)
     * @param entry o que foi feito
     * @throws IllegalStateException se não houver transação ativa (o histórico só vale junto com a ação)
     */
    void record(OwnerId owner, Entry entry);

    /**
     * Uma ação auditada.
     *
     * @param category   {@code estoque}, {@code receita}, {@code preco}, {@code cupom}, {@code pedido},
     *                   {@code financeiro}, {@code cardapio} ou {@code pix_presencial}
     * @param action     {@code criou}, {@code editou}, {@code excluiu}, {@code aplicou}, {@code ajustou}, …
     * @param recordType tipo do registro afetado ({@code ingredient}, {@code recipe}, {@code coupon}, …)
     * @param recordId   id do registro afetado
     * @param before     valores antes (ou {@code null} se não havia)
     * @param after      valores depois (ou {@code null} se deixou de existir)
     * @param details    frase legível ("Estoque de Farinha: 1000 para 800 g")
     * @param location   tela de origem; {@code null} deixa o adaptador deduzir pela rota
     */
    record Entry(String category, String action, String recordType, String recordId, Map<String, Object> before,
            Map<String, Object> after, String details, String location) {

        /**
         * Entrada sem local explícito (vale a rota da requisição).
         *
         * @param category   categoria
         * @param action     ação
         * @param recordType tipo do registro
         * @param recordId   id do registro
         * @param before     antes
         * @param after      depois
         * @param details    descrição
         * @return a entrada
         */
        public static Entry of(String category, String action, String recordType, Object recordId,
                Map<String, Object> before, Map<String, Object> after, String details) {
            return new Entry(category, action, recordType, String.valueOf(recordId), before, after, details, null);
        }
    }
}
