-- Pedido pago pelo link de pagamento vira pedido do app (devolução e avisos iguais): cliente "convidado" + shop_orders ligado.
ALTER TABLE customers ADD COLUMN guest BOOLEAN NOT NULL DEFAULT false;          -- cliente criado pelo link: sem senha nem Google, não entra
ALTER TABLE shop_orders ADD COLUMN manual BOOLEAN NOT NULL DEFAULT false;       -- veio de um pedido registrado à mão pago pelo link
ALTER TABLE order_pay_links ADD COLUMN shop_order_id INT REFERENCES shop_orders(id) ON DELETE RESTRICT;
