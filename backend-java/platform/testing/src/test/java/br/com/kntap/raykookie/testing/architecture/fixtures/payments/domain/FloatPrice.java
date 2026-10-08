package br.com.kntap.raykookie.testing.architecture.fixtures.payments.domain;

/**
 * Preço em ponto flutuante: proibido no domínio de dinheiro.
 *
 * @param amount valor em reais
 */
public record FloatPrice(double amount) {
}
