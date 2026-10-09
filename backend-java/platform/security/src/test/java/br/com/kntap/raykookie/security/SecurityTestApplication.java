package br.com.kntap.raykookie.security;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aplicação dos testes de acesso: persistência, borda web (erros em JSON) e segurança de verdade, com
 * rotas de teste de cada nível de acesso.
 */
@SpringBootApplication(scanBasePackages = "br.com.kntap.raykookie")
public class SecurityTestApplication {

    /**
     * Rotas de teste.
     */
    @RestController
    static class Routes {

        /** Quem está chamando. */
        private final CurrentActor actor;

        /**
         * Cria as rotas.
         *
         * @param actor quem está chamando
         */
        Routes(CurrentActor actor) {
            this.actor = actor;
        }

        /**
         * Rota que esqueceu o {@code @Access}.
         *
         * @return nunca devolve
         */
        @GetMapping("/t/sem-access")
        public Map<String, Object> missing() {
            return Map.of("aberta", true);
        }

        /**
         * Rota da dona.
         *
         * @return o id da dona
         */
        @Access(AccessLevel.OWNER)
        @GetMapping("/t/dona")
        public Map<String, Object> owner() {
            OwnerId id = actor.requireOwner();
            return Map.of("owner", id.value());
        }

        /**
         * Rota de cliente.
         *
         * @return o id do cliente
         */
        @Access(AccessLevel.CUSTOMER)
        @GetMapping("/t/cliente")
        public Map<String, Object> customer() {
            CustomerId id = actor.requireCustomer();
            return Map.of("customer", id.value());
        }

        /**
         * Rota pública que mostra mais para a dona.
         *
         * @return o id da dona, se houver sessão dela
         */
        @Access(AccessLevel.OWNER_OR_PUBLIC)
        @GetMapping("/t/opcional")
        public Map<String, Object> optional() {
            Map<String, Object> body = new HashMap<>();
            body.put("owner", actor.ownerIfAny().map(OwnerId::value).orElse(null));
            return body;
        }

        /**
         * Rota pública que tenta ler a dona sem ter exigido sessão.
         *
         * @return nunca devolve sem sessão
         */
        @Access(AccessLevel.PUBLIC)
        @GetMapping("/t/publica-pede-dona")
        public Map<String, Object> publicAskingOwner() {
            return Map.of("owner", actor.requireOwner().value());
        }
    }
}
