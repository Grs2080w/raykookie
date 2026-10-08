package br.com.kntap.raykookie.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * O IP usado nos limites de tentativa não pode ser escolhido pelo cliente (G05).
 */
class ClientIpResolverTest {

    /** Resolve IPs com o proxy local (rproxy) como único proxy confiável. */
    private final ClientIpResolver resolver = new ClientIpResolver(TrustedProxies.parse("127.0.0.1/32,::1/128"));

    /**
     * Cliente que forja "1.1.1.1" no cabeçalho: o proxy acrescenta o IP real à direita,
     * e é esse que vale.
     */
    @Test
    @DisplayName("X-Forwarded-For forjado não muda o IP do cliente")
    void forgedHeaderIsIgnored() {

        // ── 1. Requisição chegando pelo proxy local com o cabeçalho forjado + IP real.
        String ip = resolver.resolve("127.0.0.1", "1.1.1.1, 203.0.113.7");

        // ── 2. O IP real (último não confiável) é o usado.
        assertThat(ip).isEqualTo("203.0.113.7");
    }

    /**
     * Requisição que NÃO veio do proxy confiável: o cabeçalho inteiro é ignorado.
     */
    @Test
    @DisplayName("cabeçalho ignorado quando a conexão não vem de proxy confiável")
    void untrustedPeer() {

        // ── 1. Conexão direta de um IP externo trazendo cabeçalho.
        String ip = resolver.resolve("198.51.100.9", "10.0.0.1");

        // ── 2. Vale o IP da conexão.
        assertThat(ip).isEqualTo("198.51.100.9");
    }

    /**
     * Vários proxies confiáveis em cadeia são todos pulados.
     */
    @Test
    @DisplayName("cadeia de proxies confiáveis é pulada até o cliente")
    void chainOfTrustedProxies() {

        // ── 1. Uma rede interna inteira confiável e dois saltos internos à direita.
        ClientIpResolver internal = new ClientIpResolver(TrustedProxies.parse("10.0.0.0/8, 127.0.0.1"));

        // ── 2. O primeiro IP de fora, da direita para a esquerda, é o cliente.
        assertThat(internal.resolve("127.0.0.1", "6.6.6.6, 203.0.113.7, 10.1.2.3, 10.9.9.9"))
                .isEqualTo("203.0.113.7");
    }

    /**
     * Pelo proxy confiável sem cabeçalho: vale o IP da conexão (o próprio proxy).
     */
    @Test
    @DisplayName("proxy confiável sem cabeçalho: IP da conexão")
    void trustedPeerWithoutHeader() {
        assertThat(resolver.resolve("127.0.0.1", null)).isEqualTo("127.0.0.1");
    }

    /**
     * Tudo confiável (tráfego interno): o mais à esquerda.
     */
    @Test
    @DisplayName("só saltos confiáveis: o mais à esquerda")
    void allHopsTrusted() {
        assertThat(resolver.resolve("127.0.0.1", "::1, 127.0.0.1")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    /**
     * Lixo no lugar do IP que o proxy acrescentou: não vira chave escolhida pelo cliente.
     *
     * @param garbage valor que não é IP
     */
    @ParameterizedTest
    @ValueSource(strings = {"exemplo.com", "abc", "1.2.3", "unknown", ""})
    @DisplayName("salto que não é IP: para no último salto confiável, sem consultar DNS")
    void garbageHop(String garbage) {
        assertThat(resolver.resolve("127.0.0.1", "203.0.113.7, " + garbage)).isEqualTo("127.0.0.1");
    }

    /**
     * IPv6 e IPv4 em formas diferentes viram a mesma chave.
     */
    @Test
    @DisplayName("IP normalizado: a mesma chave para a mesma origem")
    void normalizesAddresses() {
        assertThat(resolver.resolve("::1", "2001:DB8:0:0:0:0:0:1")).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.resolve("0:0:0:0:0:0:0:1", "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    /**
     * Várias linhas de X-Forwarded-For são juntadas na ordem.
     */
    @Test
    @DisplayName("várias linhas do cabeçalho valem como uma lista só")
    void multipleHeaderLines() {

        // ── 1. O cliente manda uma linha forjada; o proxy acrescenta outra com o IP real.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(ClientIpResolver.FORWARDED_FOR, "1.1.1.1");
        request.addHeader(ClientIpResolver.FORWARDED_FOR, "203.0.113.7");

        // ── 2. Vale o IP real.
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    /**
     * Configuração inválida impede a subida, em vez de confiar em todo mundo ou em ninguém sem avisar.
     *
     * @param range faixa inválida
     */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1/33", "::1/129", "10.0.0.0/x", "300.1.1.1"})
    @DisplayName("faixa inválida em TRUSTED_PROXIES: erro")
    void invalidRange(String range) {
        assertThatThrownBy(() -> TrustedProxies.parse(range)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Sem proxies confiáveis, o cabeçalho nunca é lido.
     */
    @Test
    @DisplayName("TRUSTED_PROXIES vazio: cabeçalho sempre ignorado")
    void noTrustedProxies() {
        ClientIpResolver none = new ClientIpResolver(TrustedProxies.parse(""));
        assertThat(none.resolve("127.0.0.1", "203.0.113.7")).isEqualTo("127.0.0.1");
    }
}
