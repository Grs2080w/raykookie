-- LGPD: exclusão de conta por anonimização e solicitações de privacidade.
ALTER TABLE customers ADD COLUMN deleted_at TIMESTAMPTZ;

-- Pedidos e pagamentos precisam ser guardados (obrigação fiscal): apagar um cliente não pode levá-los junto.
ALTER TABLE shop_orders DROP CONSTRAINT shop_orders_customer_id_fkey;
ALTER TABLE shop_orders ADD CONSTRAINT shop_orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT;

CREATE TABLE privacy_requests (
  id           SERIAL PRIMARY KEY,
  owner_id     INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  customer_id  INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  type         TEXT NOT NULL CHECK (type IN ('acesso','correcao','anonimizacao','portabilidade','compartilhamento','revogacao','exclusao','outro')),
  details      TEXT NOT NULL DEFAULT '',
  status       TEXT NOT NULL DEFAULT 'aberta' CHECK (status IN ('aberta','concluida')),
  answer       TEXT NOT NULL DEFAULT '',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  answered_at  TIMESTAMPTZ
);
CREATE INDEX privacy_requests_owner_idx ON privacy_requests (owner_id, status, created_at);
