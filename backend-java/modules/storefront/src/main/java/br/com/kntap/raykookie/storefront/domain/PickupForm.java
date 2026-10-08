package br.com.kntap.raykookie.storefront.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import br.com.kntap.raykookie.web.NodeInput;
import java.util.Map;
import java.util.Set;

/**
 * Formulário do endereço de retirada ({@code parsePickup} de {@code backend/lib/pickup.ts}).
 *
 * @param label        apelido (padrão: a rua)
 * @param cep          CEP só com dígitos (vazio ou 8)
 * @param street       rua
 * @param number       número
 * @param complement   complemento
 * @param district     bairro
 * @param city         cidade
 * @param instructions como chegar
 */
public record PickupForm(String label, String cep, String street, String number, String complement, String district,
        String city, String instructions) {

    /** Campos que, presentes no PATCH, indicam edição do endereço. */
    public static final Set<String> FIELDS = Set.of("label", "street", "number", "district", "city", "cep",
            "complement", "instructions");

    /**
     * Lê e valida o corpo.
     *
     * @param body corpo JSON
     * @return o formulário
     * @throws InvalidInputException com a mensagem do primeiro campo inválido
     */
    public static PickupForm from(Map<String, Object> body) {

        // ── 1. Campos com os tamanhos do Node.
        String street = field(body, "street", 100);
        String number = field(body, "number", 10);
        String district = field(body, "district", 60);
        String city = field(body, "city", 60);
        String cep = NodeInput.cut(NodeInput.text(body.get("cep")).replaceAll("\\D", ""), 8);

        // ── 2. Obrigatórios, na ordem do Node.
        if (!cep.isEmpty() && cep.length() != 8) {
            throw new InvalidInputException("O CEP precisa ter 8 números.");
        }
        require(street, "Informe a rua.");
        require(number, "Informe o número (ou S/N, se não tiver).");
        require(district, "Informe o bairro.");
        require(city, "Informe a cidade.");

        // ── 3. Apelido padrão e campos opcionais.
        String label = field(body, "label", 40);
        return new PickupForm(label.isEmpty() ? street : label, cep, street, number, field(body, "complement", 60),
                district, city, field(body, "instructions", 200));
    }

    /**
     * Campo de texto limpo e cortado.
     *
     * @param body  corpo
     * @param name  campo
     * @param limit tamanho máximo
     * @return o texto
     */
    private static String field(Map<String, Object> body, String name, int limit) {
        return NodeInput.cut(NodeInput.text(body.get(name)), limit);
    }

    /**
     * Exige um campo preenchido.
     *
     * @param value   valor
     * @param message mensagem se vazio
     */
    private static void require(String value, String message) {
        if (value.isEmpty()) {
            throw new InvalidInputException(message);
        }
    }
}
