package br.com.kntap.raykookie.catalog.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formulários pequenos do painel: categoria, gasto fixo e ajustes, com as regras das rotas do Node.
 */
public final class SimpleForms {

    /** Tipos de categoria. */
    public static final Set<String> CATEGORY_KINDS = Set.of("ingredient", "recipe", "expense");

    /** Cor no formato {@code #rrggbb}. */
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Margem mínima máxima (%). */
    private static final double MAX_MIN_MARGIN = 95;

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private SimpleForms() {
    }

    /**
     * Categoria nova ou editada: nome (até 40), ícone (padrão {@code tag}) e cor (padrão {@code #e0357a}).
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException sem nome
     */
    public static Category category(Map<String, Object> body) {
        String name = NodeInput.text(body.get("name"));
        if (name.isEmpty()) {
            throw new InvalidInputException("Dê um nome para a categoria.");
        }
        String icon = NodeInput.text(body.get("icon"));
        String color = NodeInput.text(body.get("color"));
        return new Category(NodeInput.cut(name, 40), icon.isEmpty() ? "tag" : icon,
                COLOR.matcher(color).matches() ? color : "#e0357a");
    }

    /**
     * Tipo de categoria nova.
     *
     * @param body corpo JSON
     * @return o tipo
     * @throws InvalidInputException se não for um dos três
     */
    public static String categoryKind(Map<String, Object> body) {
        String kind = NodeInput.text(body.get("kind"));
        if (!CATEGORY_KINDS.contains(kind)) {
            throw new InvalidInputException("Tipo de categoria inválido.");
        }
        return kind;
    }

    /**
     * Gasto fixo novo ou editado.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException sem nome ou com valor inválido
     */
    public static FixedCost fixedCost(Map<String, Object> body) {
        String name = NodeInput.text(body.get("name"));
        if (name.isEmpty()) {
            throw new InvalidInputException("Dê um nome para o gasto.");
        }
        Long categoryId = NodeInput.optionalId(body.get("category_id"));
        String kind = "variable".equals(body.get("kind")) ? "variable" : "fixed";
        return new FixedCost(categoryId, NodeInput.cut(name, 80), kind, NodeInput.number(body.get("amount")));
    }

    /**
     * Ajustes da dona.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com produção mensal zero ou número inválido
     */
    public static Settings settings(Map<String, Object> body) {

        // ── 1. Produção mensal, a base do rateio.
        double monthlyUnits = NodeInput.number(body.get("monthly_units"), 100);
        if (monthlyUnits <= 0) {
            throw new InvalidInputException("Informe quantas unidades você produz por mês (maior que zero).");
        }

        // ── 2. Valor da hora e margem mínima (até 95%; ausente = mantém a atual).
        double hourlyRate = NodeInput.number(body.get("hourly_rate"));
        double initialMinMargin = Math.min(MAX_MIN_MARGIN, NodeInput.number(body.get("min_margin_pct"), 30));
        Object rawMin = body.get("min_margin_pct");
        Double minMargin = rawMin == null || "".equals(rawMin)
                ? null
                : Math.min(MAX_MIN_MARGIN, NodeInput.number(rawMin));
        return new Settings(hourlyRate, monthlyUnits, initialMinMargin, minMargin);
    }

    /**
     * Categoria.
     *
     * @param name  nome
     * @param icon  ícone
     * @param color cor
     */
    public record Category(String name, String icon, String color) {
    }

    /**
     * Gasto fixo.
     *
     * @param categoryId categoria (nula = nenhuma)
     * @param name       nome
     * @param kind       {@code fixed} ou {@code variable}
     * @param amount     valor no mês
     */
    public record FixedCost(Long categoryId, String name, String kind, double amount) {
    }

    /**
     * Ajustes.
     *
     * @param hourlyRate       valor da hora
     * @param monthlyUnits     unidades por mês
     * @param initialMinMargin margem mínima de quem ainda não tem ajustes
     * @param minMargin        margem mínima nova (nula = mantém)
     */
    public record Settings(double hourlyRate, double monthlyUnits, double initialMinMargin, Double minMargin) {
    }
}
