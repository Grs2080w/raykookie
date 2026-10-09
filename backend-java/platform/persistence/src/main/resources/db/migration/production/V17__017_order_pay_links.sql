-- Link de pagamento Pix para pedido registrado à mão (sem conta de cliente). Um link por pedido; o token vai na URL pública.
CREATE TABLE order_pay_links (
  id             SERIAL PRIMARY KEY,
  order_id       INT NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
  token          TEXT NOT NULL UNIQUE,
  amount         NUMERIC(12,2) NOT NULL CHECK (amount > 0),   -- o que será cobrado (pode ser menos que o total, se já houve sinal)
  mp_payment_id  TEXT,                                          -- Pix mais recente
  mp_payment_ids TEXT[] NOT NULL DEFAULT '{}',                  -- todos os Pix gerados (o webhook aceita qualquer um)
  pix_qr_code    TEXT NOT NULL DEFAULT '',
  pix_qr_base64  TEXT NOT NULL DEFAULT '',
  pix_expires_at TIMESTAMPTZ,
  pix_attempts   INT NOT NULL DEFAULT 0,
  paid_at        TIMESTAMPTZ,
  paid_mp_id     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
