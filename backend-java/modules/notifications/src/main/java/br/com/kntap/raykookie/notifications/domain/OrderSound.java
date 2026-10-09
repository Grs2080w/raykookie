package br.com.kntap.raykookie.notifications.domain;

import java.util.List;

/**
 * Sons de "novo pedido" do app de gestão ({@code shared/push.ts}). O id vira o canal Android
 * {@code pedido_<id>} e o arquivo {@code res/raw/pedido_<id>.wav}.
 */
public final class OrderSound {

    /** Os sons, na ordem do Node. */
    public static final List<String> IDS = List.of("sino", "caixa", "campainha", "suave", "alerta");

    /** Som padrão. */
    public static final String DEFAULT = "sino";

    /**
     * Sem instâncias.
     */
    private OrderSound() {
    }

    /**
     * Diz se o som existe.
     *
     * @param sound som recebido
     * @return verdadeiro se está na lista
     */
    public static boolean isValid(Object sound) {
        return sound != null && IDS.contains(sound);
    }

    /**
     * Canal Android de um som (desconhecido = o padrão).
     *
     * @param sound som
     * @return {@code pedido_<som>}
     */
    public static String channel(String sound) {
        return "pedido_" + (isValid(sound) ? sound : DEFAULT);
    }
}
