package br.com.kntap.raykookie.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nome da tela de origem pela rota da requisição ("Gestão > Estoque"). Fica num lugar só para o histórico falar a
 * mesma língua do app, e uma rota nova sem nome aparece como a própria rota (nunca em branco).
 */
public final class AuditLocations {

    /** Prefixo da rota para a tela; vale o primeiro que casar, então os mais específicos vêm antes. */
    private static final Map<String, String> BY_PREFIX = new LinkedHashMap<>();

    static {
        BY_PREFIX.put("/api/ingredients", "Gestão > Estoque de ingredientes");
        BY_PREFIX.put("/api/production", "Gestão > Estoque de produção");
        BY_PREFIX.put("/api/nota", "Gestão > Estoque de ingredientes > Nota fiscal");
        BY_PREFIX.put("/api/recipes", "Gestão > Receitas");
        BY_PREFIX.put("/api/combos", "Gestão > Cardápio > Combos");
        BY_PREFIX.put("/api/orders", "Gestão > Pedidos");
        BY_PREFIX.put("/api/coupons", "Gestão > Cupons");
        BY_PREFIX.put("/api/categories", "Gestão > Categorias");
        BY_PREFIX.put("/api/fixed-costs", "Gestão > Custos e ajustes");
        BY_PREFIX.put("/api/settings", "Gestão > Custos e ajustes");
        BY_PREFIX.put("/api/goals", "Gestão > Metas");
        BY_PREFIX.put("/api/price-alerts", "Gestão > Fornecedores e preços");
        BY_PREFIX.put("/api/prices", "Gestão > Fornecedores e preços");
        BY_PREFIX.put("/api/suppliers", "Gestão > Fornecedores e preços");
        BY_PREFIX.put("/api/devolucoes", "Gestão > Devoluções");
        BY_PREFIX.put("/api/venda-presencial", "Venda Presencial");
        BY_PREFIX.put("/api/webhooks/mercadopago", "Sistema > Webhook Mercado Pago");
        BY_PREFIX.put("/api/cliente/pedidos", "Loja > Pedido do cliente");
        BY_PREFIX.put("/api/pagar", "Loja > Link de pagamento");
    }

    /**
     * Classe só de funções estáticas.
     */
    private AuditLocations() {
    }

    /**
     * A tela de uma rota.
     *
     * @param path caminho da requisição (sem a query), ou {@code null} fora de uma requisição
     * @return o nome da tela
     */
    public static String of(String path) {
        if (path == null || path.isBlank()) {
            return "Sistema";
        }
        for (Map.Entry<String, String> entry : BY_PREFIX.entrySet()) {
            if (path.equals(entry.getKey()) || path.startsWith(entry.getKey() + "/")) {
                return entry.getValue();
            }
        }
        return "API " + path;
    }
}
