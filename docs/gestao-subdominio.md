# Gestão em subdomínio próprio

O painel de gestão mora só em `business.raykookie.com.br`; `raykookie.com.br` serve só o site e o cliente.
Quem decide é `frontend/middleware.ts`, pelo cabeçalho `Host`:

| Host | `/` | Rotas da gestão (`/dashboard`, `/login`…) | Rotas do site (`/cardapio`, `/cliente`…) |
|---|---|---|---|
| `business.raykookie.com.br` | vai para `/dashboard` | abrem (com `noindex`) | redirecionam para o site |
| `raykookie.com.br` | site | redirecionam para o subdomínio de gestão | abrem |
| `localhost`, previews da Vercel | site | abrem | abrem |

Rotas do painel (primeiro segmento, lista `GESTAO_ROUTES` em `frontend/middleware.ts`): `dashboard`, `estoque`, `estoque-producao`… e,
desde a paridade com o app de gestão, **`cardapio-do-site`** (Cardápio e combos; o nome é diferente de `/cardapio`
porque esse é a página pública do site) e **`cupons`**. Rota nova do painel precisa entrar nessa lista, senão o domínio
de gestão a manda para o site.

`/api`, `/_next` e arquivos com extensão não passam pelo middleware. A API continua pelo rewrite do `vercel.json`
(mesmo domínio, sem CORS) e o cookie de sessão não tem `Domain`, então vale no subdomínio novo; quem estava logado no
domínio antigo entra de novo.

Os endereços vêm de `config/urls.json` (ver `docs/urls.md`). Variáveis na Vercel (opcionais; só se quiser sobrepor o arquivo):

- `SITE_URL` = `https://raykookie.com.br`
- `GESTAO_HOST` = `business.raykookie.com.br`

## Configurar

1. **Vercel:** projeto do frontend → *Settings → Domains → Add* → `business.raykookie.com.br`. A Vercel mostra o
   registro DNS esperado (um `CNAME` para `cname.vercel-dns.com`, ou o valor próprio do projeto que ela exibir).
2. **Cloudflare:** DNS do `raykookie.com.br` → *Add record* → tipo `CNAME`, nome `business`, destino o valor que a
   Vercel mostrou, **proxy desligado (nuvem cinza, "DNS only")**.
3. **Vercel:** volte em *Domains*; quando o domínio ficar com o visto (certificado emitido), está no ar.
4. **Código:** o deploy da Vercel sai da branch ligada ao projeto (normalmente `main`). Faça o merge do PR; antes, o
   preview do PR já mostra o site sem redirecionamento (previews não são tratados como domínio de produção).
