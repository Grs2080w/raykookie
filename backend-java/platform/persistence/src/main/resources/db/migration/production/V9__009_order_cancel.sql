-- Cancelamento do pedido pelo cliente (site e app).
ALTER TABLE shop_orders ADD COLUMN cancelled_at   TIMESTAMPTZ;
ALTER TABLE shop_orders ADD COLUMN cancel_reason  TEXT NOT NULL DEFAULT '';
