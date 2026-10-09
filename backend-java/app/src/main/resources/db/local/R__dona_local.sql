-- Só no perfil "local" (application-local.yml): a dona de desenvolvimento, usuário "rayka", senha "rayka-local".
-- Hash scrypt no formato do Node (N=16384, r=8, p=1, 64 bytes), gerado com crypto.scryptSync.
INSERT INTO users (username, password_hash)
VALUES ('rayka', 'scrypt$6c6f63616c2d6465762d73616c742121$b0ae1e527a98938cc0e8d8d15be01a5658fbb94436ec8280cbd59fef85c57706a0be00cbda4f1ccadeef6963048db5fbbbda7c230aa753f13ba5645b20f690ef')
ON CONFLICT DO NOTHING;
