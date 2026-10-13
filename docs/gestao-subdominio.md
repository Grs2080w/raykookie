# Gestão em subdomínio próprio

O painel de gestão mora só em `raykookie-gestao.kntap.com.br`; `raykookie.kntap.com.br` serve só o site e o cliente.
Quem decide é `frontend/middleware.ts`, pelo cabeçalho `Host`:

| Host | `/` | Rotas da gestão (`/dashboard`, `/login`…) | Rotas do site (`/cardapio`, `/cliente`…) |
|---|---|---|---|
| `raykookie-gestao.kntap.com.br` | vai para `/dashboard` | abrem (com `noindex`) | redirecionam para o site |
| `raykookie.kntap.com.br` | site | redirecionam para o subdomínio de gestão | abrem |
| `localhost`, previews da Vercel | site | abrem | abrem |

`/api`, `/_next` e arquivos com extensão não passam pelo middleware. A API continua pelo rewrite do `vercel.json`
(mesmo domínio, sem CORS) e o cookie de sessão não tem `Domain`, então vale no subdomínio novo; quem estava logado no
domínio antigo entra de novo.

Variáveis (Vercel → *Settings → Environment Variables*, opcionais; os padrões já são estes):

- `SITE_URL` = `https://raykookie.kntap.com.br`
- `GESTAO_HOST` = `raykookie-gestao.kntap.com.br`

## Configurar

1. **Vercel:** projeto do frontend → *Settings → Domains → Add* → `raykookie-gestao.kntap.com.br`. A Vercel mostra o
   registro DNS esperado (um `CNAME` para `cname.vercel-dns.com`, ou o valor próprio do projeto que ela exibir).
2. **Cloudflare:** DNS do `kntap.com.br` → *Add record* → tipo `CNAME`, nome `raykookie-gestao`, destino o valor que a
   Vercel mostrou, **proxy desligado (nuvem cinza, "DNS only")**.
3. **Vercel:** volte em *Domains*; quando o domínio ficar com o visto (certificado emitido), está no ar.
4. **Código:** o deploy da Vercel sai da branch ligada ao projeto (normalmente `main`). Faça o merge do PR; antes, o
   preview do PR já mostra o site sem redirecionamento (previews não são tratados como domínio de produção).
