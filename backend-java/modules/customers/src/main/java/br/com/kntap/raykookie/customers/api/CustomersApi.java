package br.com.kntap.raykookie.customers.api;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import java.util.Optional;

/**
 * O que os outros módulos podem pedir ao {@code customers}.
 */
public interface CustomersApi {

    /**
     * Resumo de um cliente (para pedidos, avisos e devoluções).
     *
     * @param id id do cliente
     * @return o resumo, se o cliente existir
     */
    Optional<CustomerSummary> byId(CustomerId id);

    /**
     * Resumo de um cliente.
     *
     * @param id    id
     * @param email e-mail (minúsculo)
     * @param name  nome
     * @param phone telefone (só dígitos)
     * @param guest cliente convidado do link de pagamento (não entra)
     */
    record CustomerSummary(CustomerId id, String email, String name, String phone, boolean guest) {
    }
}
