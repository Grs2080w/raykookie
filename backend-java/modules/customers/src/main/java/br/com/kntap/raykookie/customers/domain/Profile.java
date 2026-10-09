package br.com.kntap.raykookie.customers.domain;

import br.com.kntap.raykookie.kernel.InvalidInputException;
import java.util.Map;

/**
 * Dados que o cliente corrige em "Meus dados", já limpos e conferidos com as regras do Node.
 *
 * @param name       nome (2 a 80)
 * @param phone      telefone, só dígitos (vazio ou 10/11)
 * @param cep        CEP, só dígitos (vazio ou 8)
 * @param street     rua (até 120)
 * @param number     número (até 20)
 * @param complement complemento (até 60)
 * @param district   bairro (até 80)
 * @param city       cidade (até 80)
 */
public record Profile(String name, String phone, String cep, String street, String number, String complement,
        String district, String city) {

    /**
     * Lê e confere o corpo do {@code PATCH /api/cliente/me}.
     *
     * @param body corpo JSON (campos ausentes valem vazio)
     * @return os dados limpos
     * @throws InvalidInputException com a mensagem do Node para o primeiro campo errado
     */
    public static Profile from(Map<String, Object> body) {

        // ── 1. Limpa cada campo como o Node.
        Profile profile = new Profile(CustomerText.collapse(body.get("name"), 80),
                CustomerText.digits(body.get("phone")), CustomerText.digits(body.get("cep")),
                CustomerText.collapse(body.get("street"), 120),
                CustomerText.collapse(body.get("number"), 20), CustomerText.collapse(body.get("complement"), 60),
                CustomerText.collapse(body.get("district"), 80), CustomerText.collapse(body.get("city"), 80));

        // ── 2. Confere nome, telefone e CEP.
        if (profile.name().length() < 2) {
            throw new InvalidInputException("Informe seu nome.");
        }
        int phoneLength = profile.phone().length();
        if (phoneLength > 0 && phoneLength != 10 && phoneLength != 11) {
            throw new InvalidInputException("Telefone inválido. Use DDD + número.");
        }
        if (!profile.cep().isEmpty() && profile.cep().length() != 8) {
            throw new InvalidInputException("CEP inválido.");
        }
        return profile;
    }
}
