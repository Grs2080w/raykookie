package br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.in.web;

import br.com.kntap.raykookie.testing.architecture.fixtures.Access;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller no lugar certo e com acesso declarado.
 */
@RestController
public class GuardedController {

    /**
     * Rota protegida.
     *
     * @return texto fixo
     */
    @Access
    @GetMapping("/guarded")
    public String guarded() {
        return "ok";
    }

    /**
     * Método público sem mapeamento: não precisa de {@code @Access}.
     *
     * @return texto fixo
     */
    public String helper() {
        return "ok";
    }
}
