-- Devolução de Pix: solicitações, fotos, histórico (auditoria) e o que falta para devolver com segurança.
ALTER TABLE shop_orders
  ADD COLUMN paid_mp_id        TEXT,                                   -- qual dos pagamentos (mp_payment_ids) foi aprovado: é ele que se devolve
  ADD COLUMN delivered_at      TIMESTAMPTZ,                            -- quando o último item vivo foi entregue: o prazo de 48h conta daqui
  ADD COLUMN refunded_total    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (refunded_total >= 0),
  ADD COLUMN cancel_reason_code TEXT,
  ADD COLUMN cancelled_by      TEXT CHECK (cancelled_by IN ('cliente','loja','sistema'));

UPDATE shop_orders SET paid_mp_id = mp_payment_id WHERE paid_at IS NOT NULL AND paid_mp_id IS NULL;
UPDATE shop_orders so SET delivered_at = now()                           -- já entregues hoje: ganham a janela inteira de 48h
 WHERE so.cancelled_at IS NULL
   AND EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status = 'entregue')
   AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.shop_order_id = so.id AND o.status NOT IN ('entregue','cancelado'));

CREATE TABLE refunds (
  id               SERIAL PRIMARY KEY,
  shop_order_id    INT NOT NULL REFERENCES shop_orders(id) ON DELETE RESTRICT,
  owner_id         INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  customer_id      INT NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
  kind             TEXT NOT NULL CHECK (kind IN ('automatico','solicitado','loja','sistema')),
  status           TEXT NOT NULL CHECK (status IN ('solicitado','desistiu','negado','aprovado','processando','concluido','falhou')),
  reason_code      TEXT NOT NULL DEFAULT '',
  description      TEXT NOT NULL DEFAULT '',
  items            JSONB NOT NULL DEFAULT '[]',                          -- [{id, name, qty}] marcados pelo cliente
  requested_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (requested_amount >= 0),
  approved_amount  NUMERIC(12,2) CHECK (approved_amount IS NULL OR approved_amount >= 0),
  decision_code    TEXT NOT NULL DEFAULT '',
  decision_note    TEXT NOT NULL DEFAULT '',
  decided_at       TIMESTAMPTZ,
  mp_refund_id     TEXT,
  mp_status        TEXT,
  fail_reason      TEXT CHECK (fail_reason IN ('saldo','prazo','erro')),
  attempts         INT NOT NULL DEFAULT 0,
  next_try_at      TIMESTAMPTZ,
  priority         BOOLEAN NOT NULL DEFAULT false,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  refunded_at      TIMESTAMPTZ
);
CREATE INDEX refunds_owner_status_idx ON refunds (owner_id, status, created_at);
CREATE INDEX refunds_order_idx ON refunds (shop_order_id);
-- uma solicitação "viva" por pedido (desistir libera uma nova)
CREATE UNIQUE INDEX refunds_one_request_idx ON refunds (shop_order_id) WHERE kind = 'solicitado' AND status <> 'desistiu';

-- Fotos da solicitação: tabela à parte, para a lista nunca carregar os bytes (mesmo padrão de recipe_photos).
CREATE TABLE refund_photos (
  id           SERIAL PRIMARY KEY,
  refund_id    INT NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
  idx          SMALLINT NOT NULL CHECK (idx BETWEEN 1 AND 5),
  content_type TEXT NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
  data         BYTEA NOT NULL,
  size         INT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (refund_id, idx)
);

-- Histórico de tudo o que acontece com o pedido (auditoria; principalmente devoluções).
CREATE TABLE order_events (
  id            BIGSERIAL PRIMARY KEY,
  shop_order_id INT NOT NULL REFERENCES shop_orders(id) ON DELETE CASCADE,
  at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor         TEXT NOT NULL CHECK (actor IN ('cliente','loja','sistema','mercadopago')),
  type          TEXT NOT NULL,
  data          JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX order_events_order_idx ON order_events (shop_order_id, at);
