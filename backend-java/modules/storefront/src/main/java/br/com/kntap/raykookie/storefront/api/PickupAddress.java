package br.com.kntap.raykookie.storefront.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endereço de retirada da loja.
 *
 * @param id           id
 * @param label        apelido
 * @param cep          CEP (só dígitos)
 * @param street       rua
 * @param number       número
 * @param complement   complemento
 * @param district     bairro
 * @param city         cidade
 * @param instructions como chegar
 * @param active       se é o endereço atual
 */
public record PickupAddress(long id, String label, String cep, String street, String number, String complement,
        String district, String city, String instructions, boolean active) {

    /**
     * "Rua das Flores, 120 (apto 4) - Jardim Paulista, São Paulo" ({@code formatAddress}).
     *
     * @return o endereço em uma linha
     */
    public String address() {
        return street + ", " + number + (complement.isEmpty() ? "" : " (" + complement + ")") + " - " + district + ", "
                + city;
    }

    /**
     * Texto que fica no pedido: endereço e, se houver, as instruções ({@code formatPickupLine}).
     *
     * @return a linha
     */
    public String pickupLine() {
        return instructions.isEmpty() ? address() : address() + " · " + instructions;
    }

    /**
     * O endereço no formato do Node ({@code withAddress}).
     *
     * @return o mapa para o JSON
     */
    public Map<String, Object> json() {

        // ── 1. Identificação e endereço.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("label", label);
        json.put("cep", cep);
        json.put("street", street);
        json.put("number", number);
        json.put("complement", complement);
        json.put("district", district);
        json.put("city", city);

        // ── 2. Instruções, se é o atual e o endereço em uma linha.
        json.put("instructions", instructions);
        json.put("is_active", active);
        json.put("address", address());
        return json;
    }
}
