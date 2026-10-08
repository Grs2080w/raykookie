package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Confere o {@link Access} de cada rota antes de ela rodar. Fail-closed: rota sem {@code @Access}
 * responde 500 e registra {@code ERROR} — esquecer a anotação nunca deixa uma rota aberta.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    /** Log das rotas sem declaração de acesso. */
    private static final Logger LOG = LoggerFactory.getLogger(AccessInterceptor.class);

    /** Quem está por trás de cada token. */
    private final ActorResolver actors;

    /** A dona da loja. */
    private final ShopOwner shopOwner;

    /**
     * Cria o interceptador.
     *
     * @param actors    resolvedor de sessões
     * @param shopOwner a dona da loja
     */
    public AccessInterceptor(ActorResolver actors, ShopOwner shopOwner) {
        this.actors = actors;
        this.shopOwner = shopOwner;
    }

    /**
     * Libera ou recusa a requisição conforme o nível de acesso da rota.
     *
     * @param request  requisição
     * @param response resposta
     * @param handler  a rota escolhida pelo Spring
     * @return verdadeiro se a rota pode rodar
     * @throws UnauthorizedException          sem a sessão exigida (401)
     * @throws MissingAccessDeclarationException rota sem {@code @Access} (500)
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // ── 1. Só rotas de controller passam por aqui; a página de erro do servidor não é rota da API.
        if (!(handler instanceof HandlerMethod method) || method.getBean() instanceof ErrorController) {
            return true;
        }

        // ── 2. Rota sem @Access: recusa (fail-closed) e avisa no log.
        Access access = method.getMethodAnnotation(Access.class);
        if (access == null) {
            LOG.error("Rota sem @Access recusada: {}", method.getMethod().toGenericString());
            throw new MissingAccessDeclarationException(method.getMethod().toGenericString());
        }

        // ── 3. Aplica o nível declarado.
        switch (access.value()) {
            case OWNER -> request.setAttribute(CurrentActor.OWNER, requireShopOwner(request));
            case CUSTOMER -> request.setAttribute(CurrentActor.CUSTOMER, requireCustomer(request));
            case OWNER_OR_PUBLIC -> shopOwnerIfAny(request)
                    .ifPresent(id -> request.setAttribute(CurrentActor.OWNER, id));
            case PUBLIC, PAY_LINK_TOKEN, SIGNED_WEBHOOK -> {
                // A rota confere o token do link ou a assinatura do webhook por conta própria.
            }
        }
        return true;
    }

    /**
     * Exige sessão da dona da loja: sessão de outra conta é tratada como sem sessão (G01, G24).
     *
     * @param request requisição
     * @return o id da dona
     * @throws UnauthorizedException se não houver sessão válida da dona
     */
    private OwnerId requireShopOwner(HttpServletRequest request) {
        return shopOwnerIfAny(request)
                .orElseThrow(() -> new UnauthorizedException(UnauthorizedException.OWNER_MESSAGE));
    }

    /**
     * A dona, se a requisição trouxer uma sessão válida dela.
     *
     * @param request requisição
     * @return o id da dona, ou vazio
     */
    private Optional<OwnerId> shopOwnerIfAny(HttpServletRequest request) {
        return SessionTokens.fromRequest(request, SessionTokens.OWNER_COOKIE)
                .map(SessionTokens::hash)
                .flatMap(actors::owner)
                .filter(shopOwner::is);
    }

    /**
     * Exige sessão de cliente.
     *
     * @param request requisição
     * @return o id do cliente
     * @throws UnauthorizedException se não houver sessão válida de cliente
     */
    private CustomerId requireCustomer(HttpServletRequest request) {
        return SessionTokens.fromRequest(request, SessionTokens.CUSTOMER_COOKIE)
                .map(SessionTokens::hash)
                .flatMap(actors::customer)
                .orElseThrow(() -> new UnauthorizedException(UnauthorizedException.CUSTOMER_MESSAGE));
    }
}
