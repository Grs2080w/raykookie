package br.com.kntap.raykookie.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O próprio harness: assinatura igual à do Node e recusa de banco que não é descartável.
 */
class HarnessTest {

    /**
     * Vetor de ouro do Apêndice B (gerado no Node).
     */
    @Test
    @DisplayName("assinatura do webhook igual à do Node")
    void webhookSignatureMatchesNode() {

        // ── 1. HMAC do manifesto conhecido.
        String hmac = MercadoPagoFake.hmacSha256Hex("segredo-webhook", "id:123456;request-id:req-1;ts:1700000000;");

        // ── 2. Mesmo valor do Node, e o cabeçalho completo no formato ts=…,v1=….
        assertThat(hmac).isEqualTo("5d8a4111a1d2d2e831b3436b3448176d291c922a704d059220b048e0b21b9231");
        assertThat(MercadoPagoFake.signWebhook("segredo-webhook", "123456", "req-1", 1_700_000_000L))
                .isEqualTo("ts=1700000000,v1=" + hmac);
    }

    /**
     * Banco que não termina em _test: a suíte se recusa a rodar.
     */
    @Test
    @DisplayName("CONTRACT_DB_URL fora de um banco _test: recusa")
    void refusesNonTestDatabase() {
        assertThatThrownBy(() -> ContractTarget.from(Map.of("CONTRACT_TARGET", "node",
                "CONTRACT_BASE_URL", "http://127.0.0.1:1", "CONTRACT_DB_URL", "jdbc:postgresql://h:5432/rayka_estoque")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("_test");
    }

    /**
     * Configuração completa é lida das variáveis.
     */
    @Test
    @DisplayName("lê o alvo das variáveis")
    void readsTarget() {

        // ── 1. Variáveis de um alvo Java.
        ContractTarget target = ContractTarget.from(Map.of("CONTRACT_TARGET", "java",
                "CONTRACT_BASE_URL", "http://127.0.0.1:10099/", "CONTRACT_MP_FAKE_PORT", "18080",
                "CONTRACT_DB_URL", "jdbc:postgresql://127.0.0.1:5432/rayka_contract_test?user=u&password=p"));

        // ── 2. Tudo no lugar.
        assertThat(target.isJava()).isTrue();
        assertThat(target.baseUrl()).hasToString("http://127.0.0.1:10099");
        assertThat(target.mpFakePort()).isEqualTo(18080);
        assertThat(target.shopOwner()).isEqualTo("rayka");
    }

    /**
     * Falta variável obrigatória: mensagem que diz qual.
     */
    @Test
    @DisplayName("sem CONTRACT_BASE_URL: erro que cita a variável")
    void missingVariable() {
        assertThatThrownBy(() -> ContractTarget.from(Map.of("CONTRACT_TARGET", "node")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONTRACT_BASE_URL");
    }
}
