package br.com.kntap.raykookie.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Sessões criadas pelo Node têm de continuar válidas no Java: mesmo hash do token e mesma leitura do
 * token na requisição.
 */
class SessionTokensTest {

    /**
     * Vetor gerado no Node: crypto.createHash('sha256').update('token-abc').digest('hex').
     */
    @Test
    @DisplayName("hash do token idêntico ao do Node")
    void sameHashAsNode() {

        // ── 1. Calcula o hash do token conhecido.
        String hash = SessionTokens.hash("token-abc");

        // ── 2. Compara com o valor produzido pelo Node.
        assertThat(hash).isEqualTo("096157b339cf419dbc0c8def49fc2be924dc130fbacdfa7b879bee0a3078cec4");
    }

    /**
     * Token novo no formato do Node: crypto.randomBytes(32).toString('base64url').
     */
    @Test
    @DisplayName("token novo: 32 bytes em base64url sem preenchimento, sempre diferente")
    void newTokenFormat() {
        String first = SessionTokens.newToken();
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(SessionTokens.newToken()).isNotEqualTo(first);
    }

    /**
     * O Bearer vence o cookie, como no Node; a palavra Bearer não diferencia maiúsculas.
     */
    @Test
    @DisplayName("Bearer vence o cookie")
    void bearerWins() {

        // ── 1. Requisição com os dois.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer   token-do-app");
        request.setCookies(new Cookie(SessionTokens.OWNER_COOKIE, "token-do-cookie"));

        // ── 2. Vale o do cabeçalho.
        assertThat(SessionTokens.fromRequest(request, SessionTokens.OWNER_COOKIE)).contains("token-do-app");
    }

    /**
     * Sem Bearer válido, vale o cookie com o nome certo.
     */
    @Test
    @DisplayName("sem Bearer, vale o cookie do tipo de sessão pedido")
    void cookieFallback() {

        // ── 1. Cabeçalho que não é Bearer e os dois cookies.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");
        request.setCookies(new Cookie(SessionTokens.OWNER_COOKIE, "da-dona"),
                new Cookie(SessionTokens.CUSTOMER_COOKIE, "do-cliente"));

        // ── 2. Cada tipo de sessão lê o seu cookie.
        assertThat(SessionTokens.fromRequest(request, SessionTokens.OWNER_COOKIE)).contains("da-dona");
        assertThat(SessionTokens.fromRequest(request, SessionTokens.CUSTOMER_COOKIE)).contains("do-cliente");
    }

    /**
     * Sem nada (ou com cookie vazio), não há token.
     */
    @Test
    @DisplayName("sem cabeçalho nem cookie: sem token")
    void noToken() {

        // ── 1. Requisição vazia e requisição com cookie vazio.
        MockHttpServletRequest empty = new MockHttpServletRequest();
        MockHttpServletRequest blankCookie = new MockHttpServletRequest();
        blankCookie.setCookies(new Cookie(SessionTokens.OWNER_COOKIE, ""));

        // ── 2. Nenhuma tem token.
        assertThat(SessionTokens.fromRequest(empty, SessionTokens.OWNER_COOKIE)).isEmpty();
        assertThat(SessionTokens.fromRequest(blankCookie, SessionTokens.OWNER_COOKIE)).isEmpty();
    }
}
