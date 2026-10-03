-- Notificações para o cliente (app da loja) e a mensagem da loja ao cancelar, separada do motivo.
CREATE TABLE customer_devices (
  id          SERIAL PRIMARY KEY,
  customer_id INT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  token       TEXT NOT NULL UNIQUE,            -- token FCM do aparelho (um aparelho pertence a uma conta por vez)
  platform    TEXT NOT NULL DEFAULT 'android',
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX customer_devices_customer_idx ON customer_devices (customer_id);

ALTER TABLE shop_orders ADD COLUMN cancel_note TEXT NOT NULL DEFAULT '';  -- mensagem livre de quem cancelou (o motivo fica em cancel_reason_code)
-- pedidos cancelados antes desta versão guardavam "Motivo: mensagem" junto: separa o que dá
UPDATE shop_orders SET cancel_note = btrim(substr(cancel_reason, strpos(cancel_reason, ':') + 1)), cancel_reason = btrim(split_part(cancel_reason, ':', 1))
 WHERE cancel_reason_code IS NOT NULL AND strpos(cancel_reason, ':') > 0;
