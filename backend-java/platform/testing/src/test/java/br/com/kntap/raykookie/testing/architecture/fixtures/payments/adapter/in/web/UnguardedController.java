package br.com.kntap.raykookie.testing.architecture.fixtures.payments.adapter.in.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller com rota sem {@code @Access}: proibido.
 */
@RestController
public class UnguardedController {

    /**
     * Rota esquecida.
     *
     * @return texto fixo
     */
    @PostMapping("/open")
    public String open() {
        return "ok";
    }
}
