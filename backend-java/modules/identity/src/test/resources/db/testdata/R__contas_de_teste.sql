-- Contas dos testes do identity: a dona da loja (SHOP_OWNER=dona) e outra conta, ambas com a senha
-- "senha-de-teste-123" (vetor de ouro do Node, Apêndice B do plano).
INSERT INTO users (username, password_hash) VALUES ('dona', 'scrypt$00112233445566778899aabbccddeeff$216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a42dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff') ON CONFLICT DO NOTHING;
INSERT INTO users (username, password_hash) VALUES ('outra', 'scrypt$00112233445566778899aabbccddeeff$216992f0b9009b3af7a1ae842f0b167c45e4c876e89b9ebea0aae61d62c690a42dea16df88ba4d801cf641c80e33edeacb7d65df1e739162cb307c6536e905ff') ON CONFLICT DO NOTHING;
