-- Contas usadas pelos testes de acesso: a dona da loja (SHOP_OWNER=dona) e outra conta que não é a dona.
INSERT INTO users (username, password_hash) VALUES ('dona', 'scrypt$00$00') ON CONFLICT DO NOTHING;
INSERT INTO users (username, password_hash) VALUES ('outra', 'scrypt$00$00') ON CONFLICT DO NOTHING;
INSERT INTO customers (email, name) SELECT 'cliente@teste.com', 'Cliente' WHERE NOT EXISTS (SELECT 1 FROM customers);
