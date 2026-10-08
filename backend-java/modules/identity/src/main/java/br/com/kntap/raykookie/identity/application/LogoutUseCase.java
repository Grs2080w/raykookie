package br.com.kntap.raykookie.identity.application;

import br.com.kntap.raykookie.identity.application.ports.OwnerSessionStore;
import br.com.kntap.raykookie.security.SessionTokens;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Logout da dona: apaga a sessão do token, se houver (sem token, não há o que apagar).
 */
@Service
public class LogoutUseCase {

    /** Sessões. */
    private final OwnerSessionStore sessions;

    /**
     * Cria o caso de uso.
     *
     * @param sessions sessões
     */
    public LogoutUseCase(OwnerSessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * Encerra a sessão.
     *
     * @param token token da requisição, se houver
     */
    public void logout(Optional<String> token) {
        token.map(SessionTokens::hash).ifPresent(sessions::delete);
    }
}
