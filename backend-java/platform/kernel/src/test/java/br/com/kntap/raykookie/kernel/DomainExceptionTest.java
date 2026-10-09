package br.com.kntap.raykookie.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.kernel.ids.ShopOrderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Erros de domínio carregam mensagem e status HTTP; ids recusam valores inválidos.
 */
class DomainExceptionTest {

    /**
     * Cada exceção filha tem seu status fixo.
     */
    @Test
    @DisplayName("cada exceção tem o status HTTP fixo")
    void fixedStatuses() {

        // ── 1. Genérica carrega o status recebido.
        DomainException generic = new DomainException("Falhou", 418);
        assertThat(generic.getMessage()).isEqualTo("Falhou");
        assertThat(generic.httpStatus()).isEqualTo(418);

        // ── 2. Filhas têm status fixo e repassam a mensagem.
        assertThat(new NotFoundException("Sumiu").httpStatus()).isEqualTo(404);
        assertThat(new ConflictException("Já existe").httpStatus()).isEqualTo(409);
        assertThat(new InvalidInputException("Ruim").httpStatus()).isEqualTo(400);
        assertThat(new TooManyRequestsException("Calma").httpStatus()).isEqualTo(429);

        // ── 3. Mensagens preservadas.
        assertThat(new TooManyRequestsException("Calma").getMessage()).isEqualTo("Calma");
        assertThat(new NotFoundException("Sumiu").getMessage()).isEqualTo("Sumiu");
        assertThat(new ConflictException("Já existe").getMessage()).isEqualTo("Já existe");
        assertThat(new InvalidInputException("Ruim").getMessage()).isEqualTo("Ruim");
    }

    /**
     * Ids são positivos e iguais por valor.
     */
    @Test
    @DisplayName("ids exigem valor positivo e comparam por valor")
    void ids() {

        // ── 1. Valores válidos.
        assertThat(new OwnerId(1)).isEqualTo(new OwnerId(1));
        assertThat(new CustomerId(7).value()).isEqualTo(7);
        assertThat(new ShopOrderId(9).value()).isEqualTo(9);

        // ── 2. Zero e negativo são recusados.
        assertThatThrownBy(() -> new OwnerId(0)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new CustomerId(-1)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new ShopOrderId(0)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> new OwnerId(Long.MIN_VALUE)).isInstanceOf(InvalidInputException.class);
    }
}
