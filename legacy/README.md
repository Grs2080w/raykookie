# legacy/

`monolito-original/` é o app único (Next.js com telas + API + banco no mesmo projeto) de antes da divisão em
`backend/` + `frontend/` + `shared/`. Nada aqui roda, é testado ou é publicado: produção é o container Docker
de `backend/` e a Vercel com `rootDirectory=frontend`. Não edite estes arquivos — a versão viva de cada um
está em `backend/` ou `frontend/`.

Ficaram de fora desta pasta, por mexerem com banco de dados, e continuam na raiz até serem revisados à mão:
`migrations/` (001–003, já incorporadas em `backend/migrations/`) e `scripts/` (`setup-db.sh`, `migrate.mjs`).
Os equivalentes em uso são `backend/migrations/` e `backend/scripts/`.
