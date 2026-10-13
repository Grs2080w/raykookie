package br.com.kntap.raykookie.engagement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * O catálogo do servidor é o mesmo do site e dos apps: compara com {@code rules-vectors/engagement.json}, gerado de
 * {@code shared/engagement.ts} (G25).
 */
class EngagementParityTest {

    /**
     * Mesmos eventos e mesmas regras do TypeScript.
     *
     * @throws IOException se o arquivo não puder ser lido
     */
    @Test
    @DisplayName("o catálogo de eventos do Java é igual ao gerado do TypeScript")
    void catalogMatchesTypeScript() throws IOException {
        Map<?, ?> expected;
        try (InputStream in = getClass().getResourceAsStream("/vectors/engagement.json")) {
            assertThat(in).as("rules-vectors/engagement.json empacotado").isNotNull();
            expected = JsonMapper.builder().build().readValue(in, Map.class);
        }

        assertThat(EngagementCatalog.describe()).isEqualTo(expected);
    }
}
