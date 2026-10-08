package br.com.kntap.raykookie.customers.domain;

import java.util.List;

/**
 * Tipo de solicitação sobre os dados que o cliente pode fazer (LGPD), na ordem e com os rótulos de
 * {@code shared/lgpd.ts}.
 *
 * @param id    identificador gravado em {@code privacy_requests.type}
 * @param label rótulo mostrado ao cliente
 */
public record PrivacyType(String id, String label) {

    /** Prazo da loja para responder, em dias. */
    public static final int DEADLINE_DAYS = 15;

    /** Tipos aceitos em "Fazer uma solicitação sobre meus dados". */
    public static final List<PrivacyType> REQUESTABLE = List.of(
            new PrivacyType("anonimizacao", "Anonimizar, bloquear ou eliminar dados"),
            new PrivacyType("acesso", "Quero uma resposta sobre os meus dados"),
            new PrivacyType("compartilhamento", "Saber com quem compartilharam meus dados"),
            new PrivacyType("revogacao", "Revogar um consentimento"),
            new PrivacyType("outro", "Outro assunto"));

    /**
     * Diz se o cliente pode pedir este tipo.
     *
     * @param id tipo recebido
     * @return verdadeiro se está na lista
     */
    public static boolean isRequestable(Object id) {
        return REQUESTABLE.stream().anyMatch(type -> type.id().equals(id));
    }
}
