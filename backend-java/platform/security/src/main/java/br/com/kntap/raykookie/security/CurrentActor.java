package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Quem está chamando a requisição atual, como o {@link AccessInterceptor} deixou anotado. Os
 * controllers pegam daqui o {@link OwnerId} ou o {@link CustomerId} e passam aos casos de uso, que
 * nunca veem a requisição (G21).
 */
@Component
public class CurrentActor {

    /** Atributo da requisição com a dona da sessão. */
    static final String OWNER = CurrentActor.class.getName() + ".owner";

    /** Atributo da requisição com o cliente da sessão. */
    static final String CUSTOMER = CurrentActor.class.getName() + ".customer";

    /**
     * A dona da sessão; só existe em rotas {@link AccessLevel#OWNER} (ou {@code OWNER_OR_PUBLIC} com sessão).
     *
     * @return o id da dona
     * @throws UnauthorizedException se a requisição não tem sessão da dona
     */
    public OwnerId requireOwner() {
        return ownerIfAny().orElseThrow(() -> new UnauthorizedException(UnauthorizedException.OWNER_MESSAGE));
    }

    /**
     * O cliente da sessão; só existe em rotas {@link AccessLevel#CUSTOMER}.
     *
     * @return o id do cliente
     * @throws UnauthorizedException se a requisição não tem sessão de cliente
     */
    public CustomerId requireCustomer() {
        return attribute(CUSTOMER, CustomerId.class)
                .orElseThrow(() -> new UnauthorizedException(UnauthorizedException.CUSTOMER_MESSAGE));
    }

    /**
     * A dona, se a requisição tiver sessão dela.
     *
     * @return o id da dona, ou vazio
     */
    public Optional<OwnerId> ownerIfAny() {
        return attribute(OWNER, OwnerId.class);
    }

    /**
     * Lê um atributo da requisição atual.
     *
     * @param name nome do atributo
     * @param type tipo esperado
     * @param <T>  tipo esperado
     * @return o valor, ou vazio fora de uma requisição
     */
    private static <T> Optional<T> attribute(String name, Class<T> type) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        Object value = request.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}
