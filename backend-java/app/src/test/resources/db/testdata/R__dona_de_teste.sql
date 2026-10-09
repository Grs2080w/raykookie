-- A dona da loja dos testes do app (SHOP_OWNER=dona-teste). Só existe no classpath de teste.
INSERT INTO users (username, password_hash) VALUES ('dona-teste', 'scrypt$00$00') ON CONFLICT DO NOTHING;
