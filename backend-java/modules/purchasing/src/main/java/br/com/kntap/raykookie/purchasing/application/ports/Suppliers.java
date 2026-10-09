package br.com.kntap.raykookie.purchasing.application.ports;

import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fornecedores da dona (tabela {@code suppliers}).
 */
public interface Suppliers {

    /**
     * Fornecedores por nome.
     *
     * @param owner dona
     * @return as linhas no formato do Node
     */
    List<Map<String, Object>> list(OwnerId owner);

    /**
     * Cria um fornecedor.
     *
     * @param owner dona
     * @param form  dados
     * @return a linha criada
     */
    Map<String, Object> create(OwnerId owner, Form form);

    /**
     * Edita um fornecedor da dona (e limpa o último erro).
     *
     * @param owner dona
     * @param id    fornecedor
     * @param form  dados
     * @return a linha editada, ou vazio se não for da dona
     */
    Optional<Map<String, Object>> update(OwnerId owner, long id, Form form);

    /**
     * Apaga um fornecedor da dona.
     *
     * @param owner dona
     * @param id    fornecedor
     */
    void delete(OwnerId owner, long id);

    /**
     * Diz se o fornecedor é da dona.
     *
     * @param owner dona
     * @param id    fornecedor
     * @return verdadeiro se for
     */
    boolean exists(OwnerId owner, long id);

    /**
     * Sites com busca reconhecida, por nome.
     *
     * @param owner dona
     * @return id, nome e endereço de cada site
     */
    List<Site> searchableSites(OwnerId owner);

    /**
     * Marca que o site respondeu.
     *
     * @param id fornecedor
     */
    void markOk(long id);

    /**
     * Guarda o último erro do site.
     *
     * @param id    fornecedor
     * @param error mensagem (até 200)
     */
    void markError(long id, String error);

    /**
     * Dados do fornecedor.
     *
     * @param name    nome
     * @param kind    {@code site} ou {@code loja}
     * @param url     origem do site (vazio para loja)
     * @param address endereço
     * @param notes   observações
     * @param scraper plataforma reconhecida ({@code tray}) ou nulo
     */
    record Form(String name, String kind, String url, String address, String notes, String scraper) {
    }

    /**
     * Site de fornecedor.
     *
     * @param id   id
     * @param name nome
     * @param url  origem
     */
    record Site(long id, String name, String url) {
    }
}
