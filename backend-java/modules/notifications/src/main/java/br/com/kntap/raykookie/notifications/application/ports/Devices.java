package br.com.kntap.raykookie.notifications.application.ports;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;

/**
 * Porta de saída: aparelhos que recebem os avisos ({@code device_tokens} da dona, {@code customer_devices}
 * dos clientes).
 */
public interface Devices {

    /**
     * Aparelhos da dona, com o som escolhido em cada um.
     *
     * @param owner dona
     * @return os aparelhos
     */
    List<OwnerDevice> ofOwner(OwnerId owner);

    /**
     * Tokens dos aparelhos de um cliente.
     *
     * @param customer cliente
     * @return os tokens
     */
    List<String> ofCustomer(CustomerId customer);

    /**
     * Todos os aparelhos das donas (aviso de versão nova do app de gestão).
     *
     * @return os aparelhos
     */
    List<OwnerDevice> allOwners();

    /**
     * Todos os aparelhos dos clientes (aviso de versão nova do app do cliente).
     *
     * @return os tokens
     */
    List<String> allCustomers();

    /**
     * Apaga aparelhos da dona que o Firebase disse que não existem mais.
     *
     * @param tokens tokens mortos
     */
    void forgetOwnerTokens(List<String> tokens);

    /**
     * Apaga aparelhos de cliente que não existem mais.
     *
     * @param tokens tokens mortos
     */
    void forgetCustomerTokens(List<String> tokens);

    /**
     * Cadastra (ou atualiza o som de) um aparelho da dona.
     *
     * @param owner dona
     * @param token token
     * @param sound som novo, ou nulo para manter o atual (padrão no cadastro)
     */
    void registerOwner(OwnerId owner, String token, String sound);

    /**
     * Descadastra um aparelho da dona.
     *
     * @param owner dona
     * @param token token
     */
    void unregisterOwner(OwnerId owner, String token);

    /**
     * Cadastra um aparelho de cliente.
     *
     * @param customer cliente
     * @param token    token
     */
    void registerCustomer(CustomerId customer, String token);

    /**
     * Descadastra um aparelho de cliente.
     *
     * @param customer cliente
     * @param token    token
     */
    void unregisterCustomer(CustomerId customer, String token);

    /**
     * Um aparelho da dona.
     *
     * @param token token
     * @param sound som de pedido novo escolhido no aparelho
     */
    record OwnerDevice(String token, String sound) {
    }
}
