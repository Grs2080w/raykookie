package br.com.kntap.raykookie.quality;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.security.Access;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Inventário de rotas (T03): toda rota declara {@code @Access}, e o conjunto de rotas do Java é
 * exatamente o de {@code expected-routes.txt} (as linhas que não estão "pendente"), com o mesmo nível de
 * acesso. Rota a mais, a menos ou com acesso diferente quebra o build.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccessInventoryTest extends PostgresTestBase {

    /** Quantas rotas o Node tem (Apêndice A do plano). */
    private static final int NODE_ROUTES = 111;

    /** Rotas que só existem no Java (versões de receita, combos, cupons, a consulta de status do cartão, a medição de engajamento e o histórico de auditoria). */
    private static final int JAVA_ONLY_ROUTES = 24;

    /** Todas as rotas de controller da aplicação. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    /**
     * Toda rota tem {@code @Access}.
     */
    @Test
    @DisplayName("toda rota declara @Access")
    void everyRouteDeclaresAccess() {

        // ── 1. Junta as rotas sem a anotação.
        List<String> missing = new ArrayList<>();
        apiHandlers().forEach((info, handler) -> {
            if (handler.getMethodAnnotation(Access.class) == null) {
                missing.add(handler.getMethod().toGenericString());
            }
        });

        // ── 2. Nenhuma pode faltar.
        assertThat(missing).as("rotas sem @Access").isEmpty();
    }

    /**
     * As rotas do Java são exatamente as não pendentes da lista, com o mesmo acesso.
     *
     * @throws IOException se a lista não puder ser lida
     */
    @Test
    @DisplayName("as rotas do Java são exatamente as migradas da lista do Node, com o mesmo acesso")
    void routesMatchTheInventory() throws IOException {

        // ── 1. Lê a lista: todas as linhas e as já migradas.
        List<String> lines = inventoryLines();
        Set<String> migrated = new TreeSet<>();
        lines.stream().filter(line -> !line.startsWith("pendente ")).forEach(migrated::add);

        // ── 2. Monta "MÉTODO caminho ACESSO" de cada rota da aplicação.
        Set<String> actual = new TreeSet<>();
        apiHandlers().forEach((info, handler) -> actual.addAll(describe(info, handler)));

        // ── 3. A lista tem as 111 rotas do Node mais as só do Java, e o Java tem exatamente as migradas.
        assertThat(lines).as("linhas de expected-routes.txt").hasSize(NODE_ROUTES + JAVA_ONLY_ROUTES)
                .doesNotHaveDuplicates();
        assertThat(actual).as("rotas do Java (MÉTODO caminho ACESSO)").containsExactlyElementsOf(migrated);
    }

    /**
     * Rotas de controller da API (sem a página de erro do servidor).
     *
     * @return as rotas
     */
    private Map<RequestMappingInfo, HandlerMethod> apiHandlers() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!ErrorController.class.isAssignableFrom(handler.getBeanType())) {
                handlers.put(info, handler);
            }
        });
        return handlers;
    }

    /**
     * Descreve uma rota como linhas "MÉTODO caminho ACESSO" (uma por método e caminho).
     *
     * @param info    mapeamento da rota
     * @param handler método da rota
     * @return as linhas; rota sem método HTTP aparece como "QUALQUER"
     */
    private static List<String> describe(RequestMappingInfo info, HandlerMethod handler) {

        // ── 1. Nível de acesso declarado e métodos HTTP aceitos pela rota.
        Access access = handler.getMethodAnnotation(Access.class);
        String level = access == null ? "SEM_ACCESS" : access.value().name();
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        List<String> verbs = methods.isEmpty()
                ? List.of("QUALQUER")
                : methods.stream().map(RequestMethod::name).toList();

        // ── 2. Uma linha por combinação de método e caminho.
        List<String> lines = new ArrayList<>();
        for (String verb : verbs) {
            info.getPatternValues().forEach(path -> lines.add(verb + " " + path + " " + level));
        }
        return lines;
    }

    /**
     * Linhas de {@code expected-routes.txt}, sem comentários e com os espaços normalizados.
     *
     * @return as linhas
     * @throws IOException se o arquivo não puder ser lido
     */
    private static List<String> inventoryLines() throws IOException {
        try (InputStream in = AccessInventoryTest.class.getResourceAsStream("/expected-routes.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.replaceAll("\\s+", " "))
                    .toList();
        }
    }
}
