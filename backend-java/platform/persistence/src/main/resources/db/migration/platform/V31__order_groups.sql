-- Pedido com vários itens: cada item continua sendo uma linha de `orders` (numeração, metas, faturamento e
-- histórico seguem por linha) e as linhas do mesmo pedido compartilham o `group_id`. Nulo = pedido de um item só.
-- Aditiva.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS group_id UUID;
CREATE INDEX IF NOT EXISTS orders_group_idx ON orders (group_id) WHERE group_id IS NOT NULL;
