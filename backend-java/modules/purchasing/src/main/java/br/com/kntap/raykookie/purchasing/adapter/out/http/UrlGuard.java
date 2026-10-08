package br.com.kntap.raykookie.purchasing.adapter.out.http;

import java.net.URI;

/**
 * Confere um endereço antes de cada pedido (em produção, o {@code SafeUrl} com o DNS de verdade).
 */
@FunctionalInterface
public interface UrlGuard {

    /**
     * Confere o endereço.
     *
     * @param url endereço
     * @return o endereço conferido
     * @throws RuntimeException com a mensagem para a dona, se não for seguro
     */
    URI check(String url);
}
