package br.com.kntap.raykookie.catalog.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selos fixos do cardápio que a dona liga e desliga por receita: porte de {@code backend/shared/menu-tags.ts}.
 */
public final class MenuTags {

    /** Id → nome exibido, na ordem do Node. */
    public static final Map<String, String> TAGS = tags();

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private MenuTags() {
    }

    /**
     * A lista fixa.
     *
     * @return id → nome, na ordem
     */
    private static Map<String, String> tags() {
        String[][] pairs = {{"vegano", "Vegano"}, {"vegetariano", "Vegetariano"}, {"sem_gluten", "Sem glúten"},
            {"sem_lactose", "Sem lactose"}, {"contem_gluten", "Contém glúten"}, {"contem_leite", "Contém leite"},
            {"contem_ovos", "Contém ovos"}, {"contem_castanhas", "Contém castanhas"},
            {"contem_amendoim", "Contém amendoim"}, {"contem_soja", "Contém soja"}};
        Map<String, String> tags = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            tags.put(pair[0], pair[1]);
        }
        return Collections.unmodifiableMap(tags);
    }

    /**
     * Só os ids válidos, sem repetição, na ordem em que vieram ({@code cleanMenuTags}).
     *
     * @param tags valor do JSON (não lista = nenhum)
     * @return os ids válidos
     */
    public static List<String> clean(Object tags) {
        if (!(tags instanceof List<?> list)) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (Object tag : list) {
            unique.add(String.valueOf(tag));
        }
        return unique.stream().filter(TAGS::containsKey).toList();
    }

    /**
     * Nome exibido de um selo ({@code menuTagLabel}).
     *
     * @param id id do selo
     * @return o nome, ou o próprio id se não existir mais
     */
    public static String label(String id) {
        return TAGS.getOrDefault(id, id);
    }
}
