package br.com.kntap.raykookie.contract.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.kntap.raykookie.contract.ContractSeed;
import br.com.kntap.raykookie.contract.ContractTestBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feedback (Tarefa 6.1): o cliente envia com foto, a dona lista e vê a foto.
 */
class FeedbackContractIT extends ContractTestBase {

    /**
     * PNG com a assinatura.
     *
     * @return os bytes
     */
    private static byte[] png() {
        byte[] bytes = new byte[48];
        System.arraycopy(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, 0, bytes, 0, 8);
        return bytes;
    }

    /**
     * Envio e leitura.
     */
    @Test
    @DisplayName("feedback: 201 com foto; lista da dona; foto da dona e do cliente; nota inválida 400; 6 fotos 400")
    void feedback() {

        // ── 1. Envia.
        ContractSeed.Session owner = seed.owner(target.shopOwner());
        ContractSeed.Session customer = seed.customer(ContractSeed.uniqueEmail("feedback"));
        Response made = api().header("Authorization", customer.bearer()).multiPart("rating", "4")
                .multiPart("message", "Muito bom!").multiPart("fotos", "a.png", png(), "image/png")
                .post("/api/cliente/feedback");
        assertJson(made, 201, "refund-created");
        long id = made.jsonPath().getLong("id");

        // ── 2. A dona lista e vê a foto; o cliente vê a própria.
        Response list = api().header("Authorization", owner.bearer()).get("/api/feedback");
        assertJson(list, 200, "feedback-list");
        assertThat(list.jsonPath().getInt("list.find { it.id == " + id + " }.photos")).isEqualTo(1);
        assertThat(api().header("Authorization", owner.bearer()).get("/api/feedback/" + id + "/fotos/1")
                .getHeader("Content-Type")).isEqualTo("image/png");
        assertThat(api().header("Authorization", customer.bearer()).get("/api/cliente/feedback/" + id + "/fotos/1")
                .statusCode()).isEqualTo(200);

        // ── 3. Recusas.
        assertError(api().header("Authorization", customer.bearer()).multiPart("rating", "9")
                .post("/api/cliente/feedback"), 400, "Escolha uma nota de 1 a 5 estrelas.");
        var six = api().header("Authorization", customer.bearer()).multiPart("rating", "5");
        for (int i = 0; i < 6; i++) {
            six = six.multiPart("fotos", "f" + i + ".png", png(), "image/png");
        }
        assertError(six.post("/api/cliente/feedback"), 400, "Envie no máximo 5 fotos.");
    }
}
