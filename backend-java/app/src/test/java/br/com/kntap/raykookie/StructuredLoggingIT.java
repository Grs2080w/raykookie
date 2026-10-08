package br.com.kntap.raykookie;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.observability.OpsAlert;
import br.com.kntap.raykookie.testing.PostgresTestBase;
import br.com.kntap.raykookie.web.RequestIdFilter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * Os logs da aplicação saem em JSON (um objeto por linha), com o requestId e os campos do alerta (G28).
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT extends PostgresTestBase {

    /** O alertador da aplicação. */
    @Autowired
    OpsAlert alert;

    /**
     * Um alerta dentro de uma requisição vira uma linha JSON com requestId, marcador e campos.
     *
     * @param output saída capturada do console
     */
    @Test
    @DisplayName("alerta sai como JSON com requestId, marcador ALERT e campos")
    void alertIsJson(CapturedOutput output) {

        // ── 1. Alerta com um requestId no MDC, como numa requisição.
        MDC.put(RequestIdFilter.MDC_KEY, "req-json-1");
        try {
            alert.paymentProblem("Pagamento sem pedido", Map.of("pagamento", "123"));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        // ── 2. A linha do alerta é um objeto JSON com os campos esperados.
        String line = output.getOut().lines()
                .filter(text -> text.contains("Pagamento sem pedido"))
                .findFirst()
                .orElseThrow();
        assertThat(line).startsWith("{").endsWith("}");
        assertThat(line).contains("\"requestId\":\"req-json-1\"", "\"level\":\"ERROR\"", "\"ALERT\"",
                "\"alertKind\":\"payment\"");
    }
}
