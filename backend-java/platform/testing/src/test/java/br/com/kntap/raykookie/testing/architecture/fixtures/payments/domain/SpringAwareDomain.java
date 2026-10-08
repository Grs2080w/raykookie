package br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain;

import org.springframework.http.HttpStatus;

/**
 * Domínio que conhece o Spring: proibido.
 */
public final class SpringAwareDomain {

    /**
     * Devolve um status HTTP.
     *
     * @return sempre OK
     */
    public HttpStatus status() {
        return HttpStatus.OK;
    }
}
