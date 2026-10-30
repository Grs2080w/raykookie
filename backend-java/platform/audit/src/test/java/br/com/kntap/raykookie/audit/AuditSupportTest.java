package br.com.kntap.raykookie.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que nunca pode ir para o histórico, os nomes das telas e os ajudantes de texto.
 */
class AuditSupportTest {

    /**
     * Segredos pelo nome do campo, em qualquer nível.
     */
    @Test
    @DisplayName("redação: senha, token, chave e segredo somem pelo nome do campo, também em mapas e listas aninhados")
    void secretsAreHiddenByFieldName() {
        Map<String, Object> clean = AuditRedactor.redact(Map.of(
                "name", "Farinha",
                "password", "senha-boa-123",
                "access_token", "APP_USR-123",
                "MP_WEBHOOK_SECRET", "abc",
                "pix_key", "52998224725",
                "nested", Map.of("api_key", "k", "ok", 1),
                "list", List.of(Map.of("Authorization", "Bearer x", "qty", 2))));
        assertThat(clean.get("name")).isEqualTo("Farinha");
        assertThat(clean.get("password")).isEqualTo(AuditRedactor.HIDDEN);
        assertThat(clean.get("access_token")).isEqualTo(AuditRedactor.HIDDEN);
        assertThat(clean.get("MP_WEBHOOK_SECRET")).isEqualTo(AuditRedactor.HIDDEN);
        assertThat(clean.get("pix_key")).isEqualTo(AuditRedactor.HIDDEN);
        assertThat(clean.get("nested").toString()).contains("api_key=***").contains("ok=1");
        assertThat(clean.get("list").toString()).contains("Authorization=***").contains("qty=2");
    }

    /**
     * Segredos pelo aspecto do valor, mesmo num campo de nome inocente.
     */
    @Test
    @DisplayName("redação: valor com cara de token (Bearer, APP_USR-, JWT, hash scrypt) some mesmo em campo 'notes'")
    void secretsAreHiddenByValue() {
        Map<String, Object> clean = AuditRedactor.redact(Map.of(
                "notes", "Bearer abc.def.ghi",
                "ref", "APP_USR-1234567890123456-101010-0123456789abcdef0123456789abcdef-1",
                "jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghij",
                "hash", "scrypt$00112233$aabbcc",
                "text", "Bolo de cenoura"));
        assertThat(clean.get("notes")).isEqualTo("***");
        assertThat(clean.get("ref")).isEqualTo("***");
        assertThat(clean.get("jwt")).isEqualTo("***");
        assertThat(clean.get("hash")).isEqualTo("***");
        assertThat(clean.get("text")).isEqualTo("Bolo de cenoura");
        assertThat(AuditRedactor.redact(null)).isNull();
    }

    /**
     * Telas pela rota.
     */
    @Test
    @DisplayName("telas: cada rota de gestão tem o seu nome e rota desconhecida aparece como a própria rota")
    void locationsComeFromTheRoute() {
        assertThat(AuditLocations.of("/api/ingredients/7")).isEqualTo("Gestão > Estoque de ingredientes");
        assertThat(AuditLocations.of("/api/nota/importar")).isEqualTo("Gestão > Estoque de ingredientes > Nota fiscal");
        assertThat(AuditLocations.of("/api/recipes")).isEqualTo("Gestão > Receitas");
        assertThat(AuditLocations.of("/api/coupons/3")).isEqualTo("Gestão > Cupons");
        assertThat(AuditLocations.of("/api/webhooks/mercadopago")).isEqualTo("Sistema > Webhook Mercado Pago");
        assertThat(AuditLocations.of("/api/cliente/pedidos")).isEqualTo("Loja > Pedido do cliente");
        assertThat(AuditLocations.of("/api/novo-modulo/x")).isEqualTo("API /api/novo-modulo/x");
        assertThat(AuditLocations.of("/api/ingredientsX")).startsWith("API ");
        assertThat(AuditLocations.of(null)).isEqualTo("Sistema");
        assertThat(AuditLocations.of(" ")).isEqualTo("Sistema");
    }

    /**
     * Números e comparações das frases.
     */
    @Test
    @DisplayName("valores: números sem zeros à direita, comparação por valor e recorte de campos")
    void valueHelpers() {
        assertThat(AuditValues.num(new java.math.BigDecimal("1000.000"))).isEqualTo("1000");
        assertThat(AuditValues.num("45.50")).isEqualTo("45.5");
        assertThat(AuditValues.num(null)).isEqualTo("?");
        assertThat(AuditValues.num("texto")).isEqualTo("texto");
        assertThat(AuditValues.brl(12.5)).isEqualTo("R$ 12.5");
        assertThat(AuditValues.same("1.50", 1.5)).isTrue();
        assertThat(AuditValues.same(1, 2)).isFalse();
        Map<String, Object> row = Map.of("a", 1, "b", "x", "c", 3);
        assertThat(AuditValues.pick(row, List.of("a", "c"))).containsOnlyKeys("a", "c");
        assertThat(AuditValues.pick(null, List.of("a"))).isNull();
        assertThat(AuditValues.changed(Map.of("a", "1.0"), Map.of("a", "1"), List.of("a"))).isFalse();
        assertThat(AuditValues.changed(Map.of("a", 1), Map.of("a", 2), List.of("a"))).isTrue();
        assertThat(AuditValues.changed(null, Map.of("a", 2), List.of("a"))).isTrue();
    }
}
