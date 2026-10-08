package br.com.kntap.raykookie.refunds.domain;

import br.com.kntap.raykookie.kernel.Money;
import java.math.RoundingMode;

/**
 * Contas de devolução em centavos exatos (G12): o que ainda pode voltar e o valor em reais nas mensagens.
 */
public final class RefundAmounts {

    /**
     * Classe só de funções estáticas; não é instanciada.
     */
    private RefundAmounts() {
    }

    /**
     * O que ainda pode voltar: total menos o devolvido menos o que já está em andamento.
     *
     * @param total     total pago
     * @param refunded  já devolvido
     * @param committed aprovado e ainda não concluído
     * @return o restante (pode ser zero ou negativo se a conta já passou)
     */
    public static Money left(Money total, Money refunded, Money committed) {
        return total.minus(refunded).minus(committed);
    }

    /**
     * Reais com vírgula e 2 casas, sem milhar ({@code toFixed(2).replace('.', ',')}).
     *
     * @param amount valor
     * @return o texto
     */
    public static String brl(Money amount) {
        return amount.toDecimal().setScale(2, RoundingMode.UNNECESSARY).toPlainString().replace('.', ',');
    }

    /**
     * Mensagem de valor acima do que ainda pode voltar.
     *
     * @param left restante
     * @return a mensagem
     */
    public static String tooMuch(Money left) {
        return "O valor passa do que ainda pode ser devolvido (R$ " + brl(left) + ").";
    }
}
