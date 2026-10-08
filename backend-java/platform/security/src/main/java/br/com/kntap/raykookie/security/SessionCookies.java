package br.com.kntap.raykookie.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookies de sessão com os mesmos atributos do Node: {@code HttpOnly; SameSite=Lax; Path=/}, e
 * {@code Secure} quando {@code COOKIE_SECURE=true}.
 */
@Component
public class SessionCookies {

    /** Validade das sessões: 30 dias, como no Node. */
    public static final Duration SESSION_TTL = Duration.ofDays(30);

    /** {@code COOKIE_SECURE}. */
    private final boolean secure;

    /**
     * Cria a fábrica de cookies.
     *
     * @param secure {@code rayka.cookie-secure}
     */
    public SessionCookies(@Value("${rayka.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    /**
     * Cookie que abre a sessão no navegador.
     *
     * @param name  {@link SessionTokens#OWNER_COOKIE} ou {@link SessionTokens#CUSTOMER_COOKIE}
     * @param token o token
     * @return o cookie, para o cabeçalho {@code Set-Cookie}
     */
    public ResponseCookie open(String name, String token) {
        return base(name, token).maxAge(SESSION_TTL).build();
    }

    /**
     * Cookie que apaga a sessão do navegador.
     *
     * @param name nome do cookie
     * @return o cookie vencido
     */
    public ResponseCookie clear(String name) {
        return base(name, "").maxAge(Duration.ZERO).build();
    }

    /**
     * Cookie com caminho e validade próprios (ex.: {@code rk_oauth}, do login com Google pelo site).
     *
     * @param name   nome
     * @param value  valor (já codificado)
     * @param path   caminho em que o navegador manda o cookie
     * @param maxAge validade
     * @return o cookie
     */
    public ResponseCookie scoped(String name, String value, String path, Duration maxAge) {
        return base(name, value).path(path).maxAge(maxAge).build();
    }

    /**
     * Atributos comuns.
     *
     * @param name  nome
     * @param value valor
     * @return o construtor do cookie
     */
    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value).httpOnly(true).sameSite("Lax").path("/").secure(secure);
    }
}
