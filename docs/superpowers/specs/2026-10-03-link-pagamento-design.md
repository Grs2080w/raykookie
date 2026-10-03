# Link de pagamento para pedidos criados pela confeiteira

Data: 2026-10-03 · Estado: aguardando revisão

## Objetivo
Na aba Pedidos do app de gestão, a confeiteira compartilha um link de pagamento Pix de um pedido que ela
mesma criou (sem conta de cliente) e que ainda não foi pago. O link abre uma página pública, igual à do Pix
atual (QR, copia e cola, validade, "gerar novo Pix"). Confirmado o pagamento pelo Mercado Pago, o pedido vira "pago" sozinho.

## Contexto verificado no código
- Pedido manual = linha em `orders` com `shop_order_id` nulo; cliente é só texto; `payment` ∈ pendente|sinal|pago.
- Todo o Pix atual (`backend/lib/pix.ts`, webhook, devolução, `PixCopy`) é amarrado a `shop_orders` + sessão de cliente.
- O webhook usa `external_reference` como id do `shop_orders` e exige `pagamento ∈ mp_payment_ids`.
- Decisão: **não** reaproveitar `shop_orders` (exigiria cliente, regras de cancelamento/devolução e travas de edição de pedido do app). Mecanismo paralelo e pequeno.

## Dados — migration `017_order_pay_links.sql`
Tabela `order_pay_links`: `id`, `order_id` (FK `orders` ON DELETE CASCADE, UNIQUE), `token` (UNIQUE, 32 bytes
aleatórios em base64url), `amount NUMERIC(12,2) CHECK (>0)`, `mp_payment_id`, `mp_payment_ids TEXT[]`,
`pix_qr_code`, `pix_qr_base64`, `pix_expires_at`, `pix_attempts INT`, `paid_at`, `paid_mp_id`, `created_at`.
Um link por pedido; reabrir "Compartilhar" reaproveita o link. Mudar o valor antes de qualquer pagamento atualiza
`amount` e cancela o Pix pendente no MP; depois de pago, o valor não muda.

## Backend
- `POST /api/orders/:id/pagamento` (sessão da gestão), corpo `{amount}` (padrão: `qty × unit_price`).
  Exige: pedido da conta, `shop_order_id` nulo, status ≠ cancelado, `payment` ∈ pendente|sinal, `0,01 ≤ amount ≤ total`.
  Resposta: `{url, amount}` com `url = siteUrl('/pagar/<token>')`.
- Público, por token, sem sessão:
  - `GET /api/pagar/:token` → resumo (loja, nº do pedido, itens, cliente, valor), estado
    (`aberto|pago|cancelado`) e Pix ativo (`code`, `qr_base64`, `expires_in`) se houver. Sem efeitos colaterais.
  - `POST /api/pagar/:token/pix` → gera o Pix (primeiro ou novo). Ainda válido: devolve o mesmo. Vencido: consulta o MP
    antes de trocar (pode ter sido pago no último instante), cancela o antigo pendente e cria outro. Máx. 10 Pix (`MAX_PIX_ATTEMPTS`).
  - Respostas de token inexistente = 404 genérico.
- `createPixPayment` ganha parâmetro opcional `reference` (padrão: id do pedido da loja). Link usa `link:<id>` e
  idempotência `rayka-link-<id>-<tentativa>`.
- Webhook: `external_reference` com prefixo `link:` vai a um tratador próprio. Aceita só pagamento ∈ `mp_payment_ids`
  do link e `transaction_amount ≥ amount`; marca `paid_at/paid_mp_id` e `orders.payment='pago'` na mesma transação, só na
  primeira confirmação. Estorno/chargeback de pagamento de link: ignorado (devolução pelo painel do MP).
  Pagamento aprovado para link cujo pedido foi cancelado ou já estava "pago": registra no log do servidor; sem devolução automática.
- Pagador: o MP exige e-mail. `PAY_LINK_PAYER_EMAIL` no `.env`; padrão `pagamentos@<domínio do SITE_URL>`.
- Fora do escopo: devolução automática de pagamento por link, push "pedido pago" para a gestão.

## Página `/pagar/[token]` (frontend)
Pública, `robots: noindex`, sem login. Mostra pedido, itens, cliente e valor. "Pagar com Pix" (botão, não automático:
prévias de link do WhatsApp abrem a URL) chama `POST …/pix`; depois exibe QR, copia e cola e contagem regressiva
(reaproveita `PixCopy`, com o endpoint de renovar passado por prop). Consulta o `GET` a cada ~5 s e troca para
"Pagamento confirmado". Estados: pago, cancelado, link inválido, Pix indisponível (MP fora do ar).

## App de gestão (`mobile-server`)
- Card de pedido manual com `payment != 'pago'` e status ≠ cancelado: ação "Compartilhar link de pagamento".
- Folha com valor editável (preenchido com o total; validação 0,01..total) → chama o endpoint → `share_plus` com
  "Olá! Segue o link para pagar o pedido #N (R$ X): URL"; botão "Copiar link" como alternativa.
- `share_plus` e `shareText` já existem em `mobile-common` (`ui/share_menu.dart`); não há dependência nova. `docs/API.md` documenta as rotas novas.

## Segurança
Token de 256 bits (não adivinhável); rotas públicas só expõem o resumo do pedido; geração de Pix limitada por pedido;
valor nunca vem da página pública (só da gestão); webhook confere assinatura e consulta o pagamento direto no MP, como hoje.

## Testes
- Backend (Vitest, Postgres real, MP de mentira via `MP_API_BASE`): elegibilidade/permissões, valor mínimo/máximo,
  mesma URL ao reabrir, Pix novo só após vencer, pagamento por webhook (ok, valor menor, pagamento de outro link, duplicado),
  pedido cancelado/já pago, token inválido.
- Flutter: folha de compartilhamento, botão só nos pedidos elegíveis.
- Frontend: tipagem e `next build` de teste (`NEXT_DIST_DIR=.next-test`), nunca sobre `.next` de produção.

## Publicação (do usuário)
`cd backend && npm run migrate` → `bash infra/build.sh` → `sudo bash infra/install.sh` → `bash infra/publish-apps.sh all`.
Vai junto com a correção do #38118 (migration 016), ainda não publicada.
