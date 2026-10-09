-- Pagamento com cartão: parcelas (sem juros, acima de R$150) e tentativas (evita abuso de teste de cartão).
-- paid_at/paid_mp_id/mp_payment_ids já existem e são reaproveitados como estão (agnósticos quanto à forma de pagamento).
ALTER TABLE shop_orders
  ADD COLUMN installments  SMALLINT NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
  ADD COLUMN card_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE order_pay_links
  ADD COLUMN installments  SMALLINT NOT NULL DEFAULT 1 CHECK (installments BETWEEN 1 AND 12),
  ADD COLUMN card_attempts INT NOT NULL DEFAULT 0;
