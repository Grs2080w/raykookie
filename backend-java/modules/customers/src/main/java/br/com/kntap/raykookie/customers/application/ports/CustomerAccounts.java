package br.com.kntap.raykookie.customers.application.ports;

import br.com.kntap.raykookie.customers.api.CustomersApi.CustomerSummary;
import br.com.kntap.raykookie.customers.domain.Profile;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Porta de saída: contas de cliente e os dados ligados a elas. As consultas que viram JSON direto devolvem
 * mapas no formato do Node ({@code NodeRows}).
 */
public interface CustomerAccounts {

    /**
     * Credenciais de login de um cliente que não é convidado.
     *
     * @param email e-mail (minúsculo)
     * @return id, hash da senha (nulo se só Google)
     */
    Optional<Login> loginByEmail(String email);

    /**
     * Cria um cliente com senha.
     *
     * @param name         nome
     * @param email        e-mail (minúsculo)
     * @param passwordHash hash da senha
     * @return o id
     * @throws br.com.kntap.raykookie.kernel.ConflictException se o e-mail já existir
     */
    CustomerId create(String name, String email, String passwordHash);

    /**
     * Entrada pelo Google: acha pela conta Google, senão pelo e-mail (e vincula), senão cria.
     *
     * @param sub   id da conta Google
     * @param email e-mail (minúsculo)
     * @param name  nome
     * @return o id do cliente
     */
    CustomerId upsertGoogle(String sub, String email, String name);

    /**
     * Os campos de {@code GET /api/cliente/me}.
     *
     * @param id cliente
     * @return o perfil
     */
    Optional<Map<String, Object>> profile(CustomerId id);

    /**
     * Grava a correção dos dados.
     *
     * @param id      cliente
     * @param profile dados limpos
     */
    void updateProfile(CustomerId id, Profile profile);

    /**
     * "Meus dados": a conta e as contagens de pedidos e solicitações.
     *
     * @param id cliente
     * @return os dados, se a conta não foi excluída
     */
    Optional<Map<String, Object>> myData(CustomerId id);

    /**
     * Solicitações do cliente (as 20 mais recentes).
     *
     * @param id cliente
     * @return as solicitações
     */
    List<Map<String, Object>> requestsOf(CustomerId id);

    /**
     * Pedidos do cliente, para a exportação.
     *
     * @param id cliente
     * @return os pedidos
     */
    List<Map<String, Object>> exportOrders(CustomerId id);

    /**
     * Solicitações do cliente, para a exportação.
     *
     * @param id cliente
     * @return as solicitações
     */
    List<Map<String, Object>> exportRequests(CustomerId id);

    /**
     * Aparelhos de aviso do cliente, para a exportação.
     *
     * @param id cliente
     * @return os aparelhos
     */
    List<Map<String, Object>> exportDevices(CustomerId id);

    /**
     * Como a conta entra.
     *
     * @param id cliente
     * @return se tem senha e se tem Google
     */
    Optional<Link> link(CustomerId id);

    /**
     * Tira o vínculo com o Google.
     *
     * @param id cliente
     */
    void unlinkGoogle(CustomerId id);

    /**
     * Hash da senha de uma conta não excluída.
     *
     * @param id cliente
     * @return a conta (com hash nulo se só Google), ou vazio se não existe ou foi excluída
     */
    Optional<Login> activeAccount(CustomerId id);

    /**
     * Pedidos em andamento (nem entregues nem cancelados).
     *
     * @param id cliente
     * @return quantos
     */
    int openOrders(CustomerId id);

    /**
     * Anonimiza a pessoa e os pedidos dela, apaga sessões e aparelhos e registra a exclusão.
     *
     * @param id    cliente
     * @param owner a dona (para registrar a solicitação concluída), se já cadastrada
     */
    void anonymize(CustomerId id, Optional<OwnerId> owner);

    /**
     * Cria uma solicitação sobre os dados.
     *
     * @param owner    a dona
     * @param customer cliente
     * @param type     tipo
     * @param details  detalhes
     * @return o id
     */
    long createRequest(OwnerId owner, CustomerId customer, String type, String details);

    /**
     * Solicitações recebidas pela dona, com o prazo.
     *
     * @param owner        a dona
     * @param deadlineDays prazo legal em dias
     * @return até 100 solicitações, as abertas primeiro
     */
    List<Map<String, Object>> requestsFor(OwnerId owner, int deadlineDays);

    /**
     * Conclui uma solicitação aberta.
     *
     * @param id     solicitação
     * @param owner  a dona
     * @param answer resposta
     * @return verdadeiro se havia uma solicitação aberta com esse id
     */
    boolean answer(long id, OwnerId owner, String answer);

    /**
     * Resumo de um cliente.
     *
     * @param id cliente
     * @return o resumo
     */
    Optional<CustomerSummary> summary(CustomerId id);

    /**
     * Credenciais.
     *
     * @param id           cliente
     * @param passwordHash hash da senha, ou nulo
     */
    record Login(CustomerId id, String passwordHash) {
    }

    /**
     * Como a conta entra.
     *
     * @param hasPassword tem senha
     * @param hasGoogle   tem Google
     */
    record Link(boolean hasPassword, boolean hasGoogle) {
    }
}
