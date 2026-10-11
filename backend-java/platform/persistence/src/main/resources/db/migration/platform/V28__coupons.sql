-- Cupons de desconto da dona: um código, uma porcentagem (até 90%) e, opcional, um valor mínimo do pedido
-- ("a partir de" = >=, "acima de" = >). O pedido guarda o subtotal, o cupom e o desconto; total já vem com desconto
-- (é o que o Pix e o cartão cobram). Aditiva.
CREATE TABLE IF NOT EXISTS coupons (
  id            SERIAL        PRIMARY KEY,
  user_id       INT           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code          TEXT          NOT NULL,
  percent       NUMERIC(5,2)  NOT NULL CHECK (percent > 0 AND percent <= 90),
  min_total     NUMERIC(12,2) CHECK (min_total IS NULL OR min_total > 0),
  min_inclusive BOOLEAN       NOT NULL DEFAULT true,
  active        BOOLEAN       NOT NULL DEFAULT true,
  created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS coupons_code_idx ON coupons (user_id, upper(code));

ALTER TABLE shop_orders
  ADD COLUMN IF NOT EXISTS subtotal       NUMERIC(12,2),
  ADD COLUMN IF NOT EXISTS coupon_id      INT REFERENCES coupons(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS coupon_code    TEXT,
  ADD COLUMN IF NOT EXISTS discount_pct   NUMERIC(5,2),
  ADD COLUMN IF NOT EXISTS discount_total NUMERIC(12,2) NOT NULL DEFAULT 0;
