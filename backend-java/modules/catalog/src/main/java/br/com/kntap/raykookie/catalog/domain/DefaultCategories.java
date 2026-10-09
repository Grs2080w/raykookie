package br.com.kntap.raykookie.catalog.domain;

import java.util.List;

/**
 * Categorias criadas para cada conta nova, com os ícones e as cores de {@code backend/lib/defaults.ts}.
 */
public final class DefaultCategories {

    /** Todas, na ordem do Node (ingredientes, receitas, gastos). */
    public static final List<Seed> ALL = List.of(
            new Seed("ingredient", "Farinhas e amidos", "wheat", "#d9a441"),
            new Seed("ingredient", "Açúcares e adoçantes", "candy", "#e0357a"),
            new Seed("ingredient", "Laticínios", "milk", "#5b8def"),
            new Seed("ingredient", "Chocolates e cacau", "cookie", "#8a5a44"),
            new Seed("ingredient", "Ovos", "egg", "#f0a04b"),
            new Seed("ingredient", "Gorduras", "droplet", "#e6b422"),
            new Seed("ingredient", "Frutas", "apple", "#e5484d"),
            new Seed("ingredient", "Castanhas e sementes", "nut", "#a0713a"),
            new Seed("ingredient", "Recheios e coberturas", "cake-slice", "#c2438a"),
            new Seed("ingredient", "Confeitos e decoração", "sparkles", "#9b6cf0"),
            new Seed("ingredient", "Essências e corantes", "flask-conical", "#2fa4a9"),
            new Seed("ingredient", "Fermentos e aditivos", "wind", "#7a8899"),
            new Seed("ingredient", "Embalagens", "package", "#5f9d6e"),
            new Seed("ingredient", "Outros", "tag", "#8c8c99"),
            new Seed("recipe", "Bolos", "cake", "#e0357a"),
            new Seed("recipe", "Bolos no pote", "cup-soda", "#c2438a"),
            new Seed("recipe", "Brigadeiros e docinhos", "candy", "#8a5a44"),
            new Seed("recipe", "Cookies e biscoitos", "cookie", "#d9a441"),
            new Seed("recipe", "Tortas", "pie-chart", "#e5484d"),
            new Seed("recipe", "Cupcakes", "cake-slice", "#9b6cf0"),
            new Seed("recipe", "Sobremesas", "ice-cream-cone", "#5b8def"),
            new Seed("recipe", "Salgados", "sandwich", "#f0a04b"),
            new Seed("recipe", "Pães", "croissant", "#a0713a"),
            new Seed("recipe", "Bebidas", "coffee", "#2fa4a9"),
            new Seed("recipe", "Kits e presentes", "gift", "#5f9d6e"),
            new Seed("recipe", "Outros", "tag", "#8c8c99"),
            new Seed("expense", "Energia", "zap", "#e6b422"),
            new Seed("expense", "Água", "droplets", "#5b8def"),
            new Seed("expense", "Gás", "flame", "#f0a04b"),
            new Seed("expense", "Aluguel", "home", "#7a8899"),
            new Seed("expense", "Internet e telefone", "wifi", "#2fa4a9"),
            new Seed("expense", "Transporte e entrega", "truck", "#5f9d6e"),
            new Seed("expense", "Marketing", "megaphone", "#e0357a"),
            new Seed("expense", "Impostos e taxas", "receipt", "#e5484d"),
            new Seed("expense", "Equipamentos", "wrench", "#9b6cf0"),
            new Seed("expense", "Outros", "tag", "#8c8c99"));

    /**
     * Classe só de constantes; não é instanciada.
     */
    private DefaultCategories() {
    }

    /**
     * Uma categoria padrão.
     *
     * @param kind  {@code ingredient}, {@code recipe} ou {@code expense}
     * @param name  nome
     * @param icon  ícone
     * @param color cor
     */
    public record Seed(String kind, String name, String icon, String color) {
    }
}
