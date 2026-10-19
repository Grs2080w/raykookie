# Segurança e auditoria

Documento vivo: o que protege o sistema, quais rotas são públicas de propósito e o que o histórico de auditoria registra.
Os testes que provam cada item estão em `tests/security/README.md`.

## Quem acessa o quê

Toda rota declara o seu nível com `@Access` (o `ArchitectureTest` e o `AccessMatrixIT` falham se alguma não declarar):

| Nível | Quem | Exemplos |
|-------|------|----------|
| `OWNER` | só a dona da loja, com sessão válida (77 rotas) | estoque, receitas, cupons, pedidos, devoluções, histórico, ajustes |
| `CUSTOMER` | cliente com sessão (20 rotas) | `/api/cliente/pedidos`, cupom, devolução, perfil |
| `PAY_LINK_TOKEN` | quem tem o link de pagamento (token no caminho, 7 rotas) | `/api/pagar/{token}/…` |
| `SIGNED_WEBHOOK` | Mercado Pago, com `x-signature` válida (1 rota) | `/api/webhooks/mercadopago` |
| `OWNER_OR_PUBLIC` | qualquer um; a dona vê mais | `/api/downloads`, `/api/downloads/{app}`, `/api/fotos/{id}` |
| `PUBLIC` | qualquer um (18 rotas, abaixo) | |

### Rotas públicas, e por quê

| Rota | Motivo |
|------|--------|
| `GET /api/menu`, `/api/story/cardapio`, `/api/story/produto/{id}`, `/api/legal/{doc}`, `/api/pickup` | vitrine do site e do app: cardápio, imagens de compartilhar, termos e endereço de retirada |
| `GET /api/payment-config`, `/api/cliente/config` | chave pública do Mercado Pago e opções de login do cliente (nada secreto) |
| `POST /api/auth/login`, `/api/auth/logout` | entrar e sair (login tem limite de tentativas) |
| `POST /api/auth/register` | cadastro da dona; **fechado** depois da instalação (404) |
| `POST /api/cliente/cadastro`, `/login`, `/logout`, `/google/token`; `GET /api/cliente/google`, `/google/callback` | conta do cliente (limites por IP e por conta; `next` do Google só aceita caminho do próprio site) |
| `GET /api/health` | saúde do servidor (só `{"ok":true/false}`) |
| `POST /api/jobs/tick` | desativada: responde 410 (as tarefas rodam dentro do servidor) |

## Defesas principais

- **SQL**: toda consulta usa parâmetros nomeados. As poucas concatenações juntam só constantes e colunas fixas; nenhuma recebe texto do usuário.
- **Preço, quantidade, desconto e "pago"** são decididos no servidor (cardápio do servidor, cupom do cadastro, webhook conferido no Mercado Pago). O corpo do pedido só diz *o que* e *quantos*.
- **Senha**: scrypt com sal. **Sessão**: guardada como sha256 do token, com validade; logout apaga no servidor.
- **Cookie** de sessão: `HttpOnly`, `SameSite=Lax`; `Secure` sempre que a requisição chega por HTTPS (mesmo se `COOKIE_SECURE` estiver desligado).
- **Limites de tentativas** (banco): login da dona, login e cadastro do cliente, cupom, pedidos, cartão. Um `X-Forwarded-For` forjado não zera a contagem.
- **Cabeçalhos** (API e site): `nosniff`, `X-Frame-Options: DENY`, CSP, `Referrer-Policy`, HSTS. A API não responde CORS para outras origens.
- **Webhook do Mercado Pago**: assinatura obrigatória; o pagamento é sempre reconsultado na API do Mercado Pago (o corpo da notificação não vale); valor menor e pagamento de outro pedido são recusados; reenvio é idempotente.

## Histórico de auditoria

Tabela `audit_log` (migração `V30`): **somente inserção** (gatilhos do banco recusam `UPDATE`, `DELETE` e `TRUNCATE`; a API
não tem rota de escrita). Cada linha é gravada **na mesma transação** da ação, por um único ponto (`AuditTrail`): se a ação
falha, nada é gravado; se a gravação falha, a ação inteira é desfeita. Valores com cara de segredo (senha, token, chave do
Mercado Pago…) são trocados por `***` antes de gravar.

Cada linha tem: `id`, `occurred_at` (com fuso), `user_id`/`user_name`/`role`, `action`, `category`, `location` (tela de
origem), `route`, `record_type`/`record_id`, `old_value`/`new_value` (JSON), `ip` e `details` (frase legível).

### O que gera histórico

| Categoria | Ações | De onde vem |
|-----------|-------|-------------|
| `estoque` | criou, ajustou, editou, excluiu, importou | ingredientes (`/api/ingredients`) e entrada por nota fiscal (`/api/nota/importar`) |
| `preco` | editou, aplicou, importou | preço do pacote do ingrediente (edição, "usar este preço", nota fiscal); preço de venda da receita (edição e alerta de preço sugerido); ajustes de custo (valor da hora, unidades/mês, margem mínima); desconto sugerido de combos |
| `receita` | criou, editou, excluiu | receitas e as versões de cada produto (ingredientes, rendimento, preparo, qual é a padrão) |
| `cardapio` | criou, editou, excluiu | combos, categorias, o que aparece no cardápio e os selos |
| `cupom` | criou, editou, ativou, desativou, excluiu, aplicou | cupons da dona; "aplicou" = cupom usado num pedido do cliente (desconto calculado no servidor) |
| `pedido` | criou, editou, excluiu, cancelou | pedidos lançados à mão, pedidos do cliente e cancelamentos (cliente, loja ou sistema) |
| `financeiro` | criou, editou, excluiu, pagou, devolveu | quantidade/preço/pagamento do pedido, gastos fixos, pagamento confirmado pelo Mercado Pago (Pix/cartão/conciliação), devoluções e estornos |
| `pix_presencial` | gerou, editou, excluiu | QR Pix gerado na Venda Presencial e mudança/remoção da chave. **O Pix estático nasce no aparelho, sem servidor**: o app guarda os eventos numa fila e manda quando há internet; por isso cada registro vem marcado `reported_by_device`. A chave Pix nunca é enviada |

Fora do histórico, de propósito: metas mensais (são um alvo, não mudam estoque nem valor), leituras e conferências de cupom,
cotações de fornecedores (só guardam preço visto; o preço do ingrediente só muda pelos caminhos acima).

### Quem vê

`GET /api/historico` (só a dona): paginado (até 100 por página), mais recentes primeiro, com filtros de período, categoria,
ação, usuário e busca. No app de gestão: **Mais → Histórico**.
