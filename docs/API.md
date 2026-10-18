# API do backend

Usada pelo frontend (site/painel) e pelo app mobile. Tudo em JSON (UTF-8). Erros: `{ "error": "mensagem em português, pronta para mostrar" }` com o status HTTP certo (400 validação, 401 sem sessão, 404, 409 conflito, 413/415 foto, 429 muitas tentativas, 5xx).

Em produção é um único processo na porta `10005`: `/api/*` é esta API e o resto é o site. O navegador e o app mobile usam o mesmo endereço público (`https://raykookie.com.br/api/...`).

## Autenticação

Há dois tipos de conta, com sessões separadas (um token de cliente **não** abre rotas do painel e vice-versa):

| | Painel (confeiteira) | Loja (cliente) |
|---|---|---|
| Login | `POST /api/auth/login` `{username,password}` | `POST /api/cliente/login` `{email,password}` |
| Criar conta | `POST /api/auth/register` | `POST /api/cliente/cadastro` `{name,email,password}` |
| Google | — | `POST /api/cliente/google/token` `{idToken}` (app) · `GET /api/cliente/google` (site) |
| Quem sou eu | `GET /api/me` | `GET /api/cliente/me` |
| Sair | `POST /api/auth/logout` | `POST /api/cliente/logout` |

- **Navegador**: a sessão vai num cookie `httpOnly` (nada a fazer).
- **App mobile**: mande o cabeçalho `x-client: mobile` no login/cadastro; a resposta traz `{ "ok": true, "token": "..." }`. Guarde o token no cofre do aparelho e envie `Authorization: Bearer <token>` nas demais chamadas. Sessão vale 30 dias.

## Painel (exige sessão da confeiteira)

| Método e caminho | O que faz |
|---|---|
| `GET /api/dashboard` | Resumo do mês: meta, realizado, a receber, entregas próximas, estoque baixo, receitas mais lucrativas |
| `GET/POST /api/ingredients` · `PATCH/DELETE /api/ingredients/:id` | Estoque. `PATCH {stock_qty}` ajusta só a quantidade |
| `GET/POST /api/categories?kind=ingredient\|recipe\|expense` · `PATCH/DELETE /api/categories/:id` | Categorias |
| `GET/POST /api/recipes` · `GET/PUT/DELETE /api/recipes/:id` | Receitas. `GET /api/recipes` já vem precificada (`pricing`); `GET /:id` é a receita crua. Campo `unit_weight_g`: gramatura (g) de cada unidade que a receita rende (0 = não informada) |
| `PUT/DELETE /api/recipes/:id/photo` | Foto da receita: corpo **bruto** da imagem (`image/jpeg`, `png` ou `webp`, até 2 MB; o tipo é conferido pelo conteúdo) |
| `GET/POST /api/orders` · `PATCH/DELETE /api/orders/:id` | Pedidos (inclui os vindos da loja). `PATCH {status}` / `{payment}` para troca rápida |
| `GET/PUT /api/goals?month=AAAA-MM-01` | Meta do mês + realizado (`{month, goal, stats}`) |
| `GET/PUT /api/settings` | Valor da hora e unidades por mês |
| `GET/POST /api/fixed-costs` · `PATCH/DELETE /api/fixed-costs/:id` | Gastos do mês |

## Loja (público ou sessão de cliente)

| Método e caminho | O que faz |
|---|---|
| `GET /api/menu` | Cardápio = receitas com preço do usuário `SHOP_OWNER`: só `id`, `name`, `price`, `category`, `image` e `weight_g` (gramatura de cada unidade, vinda de `unit_weight_g` da receita; ausente se não informada) |
| `GET /api/fotos/:id?v=...` | Foto pública de uma receita do cardápio (imutável quando tem `v`) |
| `GET /api/cliente/config` | `{ googleEnabled }` |
| `GET/POST /api/cliente/pedidos` | Lista os pedidos do cliente / cria um. O preço **sempre** vem do servidor |
| `GET /api/health` | `{ ok: true }` se o backend e o banco respondem |

### Criar pedido (`POST /api/cliente/pedidos`)

```json
{ "items": [{ "id": "1", "qty": 3 }], "contact_name": "Maria Souza", "phone": "(11) 91234-5678",
  "fulfillment": "entrega", "cep": "01310-100", "street": "Av. Paulista", "number": "1000", "complement": "", "district": "Bela Vista", "city": "São Paulo",
  "scheduled_for": "2026-10-05", "time_window": "tarde", "payment_method": "pix", "change_for": "", "notes": "" }
```
Regras: data com pelo menos 2 dias de antecedência; `fulfillment` `retirada|entrega` (endereço só é exigido na entrega); `time_window` `manha|tarde|noite`; `payment_method` `pix|dinheiro` (`change_for` só vale em dinheiro e precisa ser ≥ total). Cada item vira uma linha na tela Pedidos do painel (status `novo`, pagamento `pendente`, com o endereço no campo `address`).
Em Pix, a resposta traz `pix: { code, qr_base64 }` (copia e cola do Mercado Pago). O pedido só passa a `pago` quando o webhook `POST /api/webhooks/mercadopago` recebe a notificação de pagamento **aprovado** (assinatura `x-signature` validada com `MP_WEBHOOK_SECRET`, pagamento reconsultado na API do MP e conferido por `external_reference` = id do pedido). Requer `MP_ACCESS_TOKEN` e `MP_WEBHOOK_SECRET`.

## Webhook do Mercado Pago (`POST /api/webhooks/mercadopago`)

URL cadastrada no painel (modo **produção**): `https://raykookie.com.br/api/webhooks/mercadopago` (o site repassa
para a API). A assinatura (`x-signature`) é conferida com `MP_WEBHOOK_SECRET`, que precisa ser a **assinatura secreta de
produção** do aplicativo (o painel tem um segredo para o modo de teste e outro para produção; gerar um novo invalida o
anterior). Assinatura errada responde 401 e deixa um aviso no log.

`MP_WEBHOOK_SECRET_TEST` (opcional) é a assinatura secreta da aba de **modo de teste** do painel. Pedidos de teste
(`ORDTST…`) notificam a mesma URL assinados com esse segredo; com ele configurado, essas notificações recebem 200
(`{"ok":true,"test":true}`) sem consultar o Mercado Pago nem tocar em pedido real. Sem a variável, são recusadas com 401.
Um `data.id` que o Mercado Pago não conhece (o botão **Simular** do painel) também recebe 200 (`ignored`), porque repetir
não adianta. Como toda variável do `backend/.env.docker`, só vale depois de recriar o container.

| Evento (`type`) | O que o sistema faz |
|---|---|
| `payment` | consulta o pagamento no Mercado Pago e, se aprovado, marca o pedido como pago (valor e dono conferidos) |
| `stop_delivery_op_wh` (alerta de fraude) | guarda `alerta_fraude` no histórico do pedido, avisa a dona por push ("não entregue") e dispara alerta de operação. Esse aviso **não é reenviado** pelo Mercado Pago |
| `order` / `orders` (API Orders) | `data.id` é o id do pedido (`ORD…`); consulta o pedido e aplica como o `payment` (a assinatura usa o id em minúsculas) |
| qualquer outro tipo | só confirma o recebimento (200) e registra no log |

Um pagamento aprovado que não é aplicado ao pedido (de outro pedido ou valor menor) também dispara alerta de operação.

### Cartão: 3DS, status e bandeiras

- **`MP_API`** escolhe a API das cobranças **novas**: `payments` (padrão) ou `orders`. Consultas, cancelamentos e
  devoluções seguem o formato do id (`ORD…` = API Orders; numérico = API Payments), então pagamentos antigos continuam
  funcionando ao trocar. Detalhes e teste de homologação em [mercadopago-orders-api.md](mercadopago-orders-api.md).
- **`MP_3DS`** (`on_fraud_risk` padrão, ou `never`): quando o Mercado Pago pede a confirmação do cartão no banco, a
  resposta do cartão traz `challenge_url` (`approved: false, pending: true`). O cliente abre o endereço num iframe/WebView
  e, ao terminar, chama **`GET /api/cliente/pedidos/{id}/cartao`** (ou `GET /api/pagar/{token}/cartao` no link), que
  consulta o Mercado Pago **sem cobrar de novo** e devolve `approved`, `pending` (com `challenge_url` se o desafio segue
  aberto) ou a recusa com `reason`. Refazer o pagamento com o desafio aberto devolve o mesmo desafio.
- O corpo do cartão aceita `card_payment_type_id` (`credit_card`, `debit_card`, `prepaid_card`); sem ele, `deb…` na
  bandeira vale débito. Débito e pré-pago são sempre à vista.
- **`GET /api/payment-config`** devolve também `brands` (meios de cartão ativos da conta: `id`, `name`, `type`, `logo`,
  em cache por 1 h; vazio se o Mercado Pago não respondeu) e `statusScreenBrick` (`MP_STATUS_SCREEN`, padrão `true`).

## Nota fiscal (NFC-e) no estoque

Usado pelo app de gestão (**Estoque → Ler nota**). O celular lê o QR code do cupom, baixa a página de consulta do portal da SEFAZ (quem acessa o portal é o aparelho, não este servidor) e manda o HTML para cá interpretar.

| Rota | O que faz |
|---|---|
| `POST /api/nota/ler` `{ url, html }` | Interpreta a página da nota. `url` precisa ser de um portal `*.gov.br`. Devolve `{ key, store, date, total, already_imported, items[] }`; cada item traz `name, qty, unit, unit_price, total`, o ingrediente do estoque que casou (`match: { ingredient_id, name, exact }`), o plano de soma (`add: { add_qty, package_cost, note }`, já convertendo kg/L e tamanhos de embalagem) e a sugestão de cadastro (`create`). `422` quando a página não tem itens legíveis (captcha, layout desconhecido) |
| `POST /api/nota/importar` `{ key, store, date, total, items[] }` | Lança tudo numa transação: `{ action: 'add', ingredient_id, add_qty, package_cost?, item_name }` soma no estoque (e lembra o "apelido" do item) e `{ action: 'create', name, unit, package_size, package_cost, stock_qty }` cria ingrediente novo. A mesma nota (chave de 44 dígitos) só entra uma vez por conta: repetida dá `400` |

`exact: true` = soma direto; `exact: false` = sugestão que o app pergunta (e lembra a resposta). Itens sem correspondência o app pergunta se a confeiteira aceita criar.

## Push de novos pedidos (app de gestão)

Quando um cliente cria um pedido (`POST /api/cliente/pedidos`), o backend avisa por push (Firebase Cloud Messaging) todos os celulares registrados do dono da loja, mesmo com o app fechado. Falha no push nunca derruba o pedido. Sem `FIREBASE_SERVICE_ACCOUNT_PATH` no `.env` o push fica desligado.

| Rota (exige sessão da confeiteira) | O que faz |
|---|---|
| `POST /api/devices` `{ token, sound? }` | Registra o celular (token FCM) ou atualiza o som. `sound` ∈ `sino`, `caixa`, `campainha`, `suave`, `alerta` (catálogo em `shared/push.ts`); som desconhecido dá `400` |
| `DELETE /api/devices?token=...` | Tira o celular da lista (o app chama antes de sair da conta) |

O push leva `data: { type: 'novo_pedido', orderId }` e usa o canal Android `pedido_<som>`, um por som (o som de uma notificação Android 8+ é do canal). Tokens que o Firebase recusa (`not-registered`) são apagados sozinhos.

Quando o webhook do Mercado Pago confirma o Pix de um pedido, os mesmos celulares recebem outro push: título `Pix recebido · Pedido #N`, texto `<cliente> pagou R$ X · N itens`, `data: { type: 'pagamento', orderId }`, sempre no canal `pedido_caixa` (existe em todo app de gestão, então funciona sem atualizar o app). Só dispara na primeira confirmação; reenvios do Mercado Pago não repetem o aviso.

## Downloads dos apps

| Método e caminho | O que faz |
|---|---|
| `GET /api/downloads` | Versões publicadas: `{ client, server }` com `version`, `build`, `size`, `sha256`, `date`, `url`. O `server` (gestão) só aparece para a confeiteira logada |
| `GET /api/downloads/client` | APK do app do cliente (público). Aceita `Range` |
| `GET /api/downloads/server` | APK do app de gestão: exige sessão da confeiteira (cookie ou Bearer) ou `?t=` de um link temporário |
| `POST /api/downloads/server/link` | Gera o link temporário (5 min) para o app abrir o download no navegador do aparelho |

## Números

Campos numéricos aceitam texto em pt-BR (`"1.500,50"`, `"7,5"`, `"1.000"` = mil) ou número JSON (`2.125`, valendo como está). Inválido ou negativo devolve 400 com mensagem.

## Tipos

Os formatos de resposta estão em [`shared/types.ts`](../shared/types.ts) (TypeScript); o app mobile tem o espelho em `mobile/lib/core/models.dart`.

## Termos de Serviço e Política de Privacidade

O texto fica em um só lugar, `shared/legal.ts` (dados da empresa em `LEGAL_ENTITY`; revise com um advogado antes de divulgar).

| Onde | Endereço |
|---|---|
| Site | `/termos` e `/privacidade` (cada documento na sua URL) |
| Apps (cliente e gestão) | `GET /api/legal/termos` e `GET /api/legal/privacidade`, públicos, devolvem `{ id, title, summary, updated, sections: [{ h, p?, ul? }] }` |

Os apps leem o texto da API, então ele pode ser atualizado sem lançar APK novo. Os dois documentos são linkados no rodapé do site e da área do cliente, nas telas de criar conta e entrar, e no menu Mais do app de gestão.

## Validade do Pix e "Gerar novo Pix"

- O código Pix nasce com validade (`PIX_EXPIRES_MINUTES`, padrão 60; o Mercado Pago aceita de 30 minutos a 30 dias). A validade vai para o Mercado Pago em `date_of_expiration` (ISO 8601, `-03:00`) e é guardada em `shop_orders.pix_expires_at`.
- `GET /api/cliente/pedidos` devolve, para pedidos Pix ainda não pagos, `pix: { code, qr_base64, expires_in, expired }`. `expires_in` são os **segundos restantes** (o app não depende do relógio do celular). Vencido, `expired` é `true` e `code`/`qr_base64` vêm vazios: ninguém deve pagar um código vencido.
- `POST /api/cliente/pedidos/{id}/pix` ("Gerar novo Pix"): só do dono do pedido, só para Pix não pago e não cancelado.
  - Código ainda válido: não troca, devolve `state: "active"`.
  - Vencido: confere no Mercado Pago se o antigo não foi pago no último instante (se foi, marca como pago, `state: "paid"`); senão cancela o antigo e gera outro com chave de idempotência nova (`rayka-order-{id}-{tentativa}`), `state: "renewed"`.
  - Limite de 10 Pix por pedido (429). Falha do Mercado Pago: 502.
- O webhook aceita a aprovação de qualquer Pix gerado para o pedido (`shop_orders.mp_payment_ids`) e só marca pago se o valor cobrir o total.

## Cancelar pedido (cliente) e código do pedido

- **Código do pedido:** o cliente vê um número aleatório de 4 ou 5 dígitos (`code`, coluna `shop_orders.public_code`, único), não o número sequencial da loja. O `id` interno continua sendo o que as rotas usam (`/api/cliente/pedidos/{id}/...`). A confeiteira vê o mesmo código nas observações do pedido (`Pedido online #4821`) e nos avisos (push).
- `POST /api/cliente/pedidos/{id}/cancelar` (corpo opcional `{ "reason": "..." }`): o cliente cancela o próprio pedido.
  - Só enquanto todos os itens estão `novo` (a confeiteira não começou a produzir) e o pedido **não foi pago**. Pedido pago ou em produção: 409, segue pelo atendimento (a devolução de Pix é feita por lá).
  - Pix pendente: confere no Mercado Pago se não foi pago neste instante (se foi, marca como pago e recusa) e desativa o código antes de cancelar.
  - Os itens viram `cancelado` (faturamento e lucro ignoram), `shop_orders.cancelled_at` e `cancel_reason` são gravados e a confeiteira recebe o push "Pedido #código cancelado". Repetir a chamada devolve `state: "already"`.
- `GET /api/cliente/pedidos` devolve `can_cancel` para o app saber se mostra o botão.

## Endereço de retirada

A confeiteira guarda os endereços que mais usa e escolhe qual vale agora (só um fica ativo). No app de gestão: **Mais → Endereço de retirada**; no painel web: **Endereço de retirada**.

| Rota | O que faz |
|---|---|
| `GET /api/pickup-addresses` | Lista os endereços guardados (painel). Cada um traz `address` já formatado ("Rua, 12 (compl) - Bairro, Cidade") e `is_active`. |
| `POST /api/pickup-addresses` | Adiciona. Campos: `label`, `cep`, `street`, `number`, `complement`, `district`, `city`, `instructions`, `active`. O primeiro vira o atual; os outros só com `active: true`. Até 20 endereços. |
| `PATCH /api/pickup-addresses/{id}` | Edita e/ou escolhe como atual (`active: true`; os demais deixam de ser). |
| `DELETE /api/pickup-addresses/{id}` | Exclui. O atual só sai se for o único (409 se houver outros). |
| `GET /api/pickup` | **Público.** O endereço atual `{ label, address, instructions }` (vazio se nenhum): o cliente vê ao escolher "Retirar no local". |

O pedido guarda o endereço de retirada **da época** (`shop_orders.pickup_address`): trocar o endereço depois não muda pedidos já feitos. O cliente o vê em Meus pedidos (`pickup_address`) e a confeiteira, no campo Endereço do pedido ("Retirada: ...").

## Link só do cardápio

- Página pública **`/cardapio`**: só o cardápio (sem hero, galeria nem depoimentos), com o botão **Compartilhar cardápio** (folha de compartilhar do aparelho; sem ela, copia o link) e metadados próprios para a prévia do link. O mesmo botão existe na seção Cardápio da landing, na tela de **Receitas** do painel web e do app de gestão e na home do app do cliente.
- **Estado na URL:** cada "Pedir" aponta para `/cliente/pedidos/novo?item={id}&from=cardapio` (ou `from=home`). Sem sessão, `requireCustomer` redireciona para `/cliente/entrar?next=<essa URL>`; depois do login (e-mail, cadastro ou Google) a pessoa cai no novo pedido com o item escolhido, e o `from` mostra "← Voltar ao cardápio". O login troca a página no histórico (`replace`), então o botão voltar do navegador também leva ao cardápio.
- **App do cliente:** o login usa `pushReplacement`: troca só a tela de login e a tela de onde a pessoa veio continua embaixo (voltar leva à home).
- O link abre no navegador. Abrir direto no app instalado exigiria Android App Links (`assetlinks.json` e `intent-filter`).

## Direitos do titular (LGPD) — app e site do cliente

Todas as rotas exigem sessão de cliente (cookie ou `Authorization: Bearer`).

| Rota | O que faz |
|---|---|
| `GET /api/cliente/me/dados` | "Meus dados": conta, nº de pedidos, pedidos em andamento, solicitações e tipos aceitos |
| `PATCH /api/cliente/me` | Correção: `name`, `phone`, `cep`, `street`, `number`, `complement`, `district`, `city` |
| `GET /api/cliente/me/exportar` | Portabilidade: JSON com conta, pedidos e solicitações (download) |
| `POST /api/cliente/me/privacidade` | Solicitação `{type, details}` (anonimizacao, acesso, compartilhamento, revogacao, outro); push para a confeiteira; prazo de resposta 15 dias |
| `DELETE /api/cliente/me/google` | Desvincula o Google (só se a conta tiver senha) |
| `DELETE /api/cliente/me` | Exclui a conta `{confirm:"EXCLUIR", password?}`. **Anonimiza**: pedidos e pagamentos ficam sem dados pessoais. 409 se houver pedido em andamento |

Gestão: `GET /api/privacy-requests` (lista, com `due_at`) e `POST /api/privacy-requests/:id` `{answer}` (conclui). Página pública: `/excluir-conta`.

## Devolução de Pix

Regras (única fonte: `shared/refund.ts`; o servidor decide e devolve `refund` pronto em `GET /api/cliente/pedidos`):

| Pedido | `refund.mode` | O que o cliente faz |
|---|---|---|
| Pago e todos os itens `novo` | `automatico` | `POST /api/cliente/pedidos/:id/cancelar` → cancela e o Pix volta **na hora** |
| Pago, em `producao` ou `pronto` | `solicitacao` | Pede devolução (fotos opcionais) |
| Pago e `entregue` há < 48h | `solicitacao` | Pede devolução com **1 a 5 fotos** (obrigatórias nos motivos de produto recebido) |
| Entregue há > 48h, já devolvido, solicitação existente, Pix com > 85 dias | `nenhum` | `why_not` explica |

Cliente (sessão de cliente):

| Rota | O que faz |
|---|---|
| `POST /api/cliente/pedidos/:id/cancelar` | `{reason_code, reason}`; motivos em `CANCEL_REASONS`. Se já pago, devolve o total e responde `{refunded}` |
| `POST /api/cliente/pedidos/:id/devolucao` | **multipart**: `reason_code`, `description` (≥ 20 car.), `items` (JSON `[{id, qty}]`), `fotos` (0–5 arquivos JPG/PNG/WebP de até 2 MB; EXIF é removido). Uma solicitação por pedido |
| `DELETE /api/cliente/pedidos/:id/devolucao` | Desiste, enquanto estiver em análise |
| `GET /api/cliente/devolucoes/:rid/fotos/:n` | Foto da própria solicitação |

Confeiteira (painel e app de gestão):

| Rota | O que faz |
|---|---|
| `GET /api/devolucoes?status=aguardando\|andamento\|concluidas\|negadas` | Lista, contadores e `pending_amount` (valor que precisa estar no Mercado Pago) |
| `GET /api/devolucoes/:id` · `/fotos/:n` | Detalhe com histórico (`order_events`) e fotos |
| `POST /api/devolucoes/:id/decisao` | `{approve:true, amount?, note}` (total, ou parcial com nota) ou `{approve:false, code, note}` |
| `POST /api/devolucoes/:id/tentar` | Refaz uma devolução que falhou (ex.: depois de pôr saldo) |
| `POST /api/orders/shop/:id/cancelar` | `{code, note}`: cancela pedido da loja; se pago, devolve tudo |
| `POST /api/orders/shop/:id/devolver` | `{amount, note}`: devolve um valor sem cancelar. Em pedido do app comum, devolver tudo o que resta encerra o pedido; em pedido manual (promovido pelo link) nunca encerra (pode ser pagamento em dobro ou só sinal) |
| `POST /api/orders/:id/pagamento` | `{amount?}`: cria (ou reaproveita) o link de pagamento Pix de um pedido registrado à mão, não pago. Devolve `{url, amount}`. `amount` (texto pt-BR ou número) vai de 0,01 ao total da linha; padrão = total |
| `GET /api/pagar/:token` | **Público.** Resumo do link: `{state: aberto\|pago\|cancelado, amount, order, pix, shop_order}`. `shop_order` é o pedido no formato do cliente (mesmo de `GET /api/cliente/pedidos`), `null` enquanto não pago. Só leitura (não gera Pix; link antigo já pago é promovido ao abrir). `404` para token malformado ou desconhecido |
| `POST /api/pagar/:token/cancelar` | **Público (token).** `{reason_code, reason}`: cancela o pedido promovido, mesmas regras de `POST /api/cliente/pedidos/{id}/cancelar`. `404` para token malformado, desconhecido ou de link não pago |
| `POST /api/pagar/:token/devolucao` | **Público (token).** Pede devolução; multipart `reason_code`, `description`, `items`, `fotos`; `201`. Mesmas regras do app. `404` como acima |
| `DELETE /api/pagar/:token/devolucao` | **Público (token).** Desiste da solicitação ainda não analisada. `404` como acima |
| `POST /api/pagar/:token/pix` | **Público.** Gera (ou renova, se venceu) o Pix do link. Devolve `{ok, state, pix}` ou `{error}`; `409` se o pedido foi cancelado ou passou a valer menos que o valor do link |

Estados da devolução: `solicitado → aprovado/negado/desistiu`; `aprovado → processando → concluido`; `falhou` (saldo, prazo ou erro) tenta de novo sozinha (15 min, 1 h, 3 h, 12 h…) e pelo botão. O cliente nunca vê "falhou": continua em processamento. `PATCH /api/orders/:id` recusa `cancelado` em pedido da loja já pago (use "cancelar").

Mercado Pago: `POST /v1/payments/{id}/refunds` com `X-Idempotency-Key: rayka-refund-{id}` (repetir nunca devolve em dobro). O webhook também trata `refunded` (inclusive devoluções feitas no painel do Mercado Pago) e devolve sozinho um Pix pago depois do cancelamento.

Tarefas periódicas: `POST /api/jobs/tick` (header `X-Jobs-Secret` = `JOBS_SECRET`), chamado a cada 5 min por `server.mjs`: refaz devoluções, concilia Pix/devoluções a cada 6 h e apaga fotos de devoluções com mais de 180 dias.

## Link de pagamento (pedido registrado à mão)

A confeiteira gera, no app de gestão, um link para o cliente pagar por Pix um pedido registrado à mão (sem `shop_order_id`), ainda não pago e não cancelado.

- Tabela `order_pay_links` (um link por pedido, com `amount`, token e `mp_payment_ids`). O link vive e morre com o pedido (`ON DELETE CASCADE`): pedido excluído = link 404.
- O Pix do link usa `external_reference = link:<id>` (o pedido da loja continua com `<id>`).
- O webhook só marca o pedido como pago se o pagamento está em `mp_payment_ids` do link e `transaction_amount` é maior ou igual ao valor cobrado. Estorno/chargeback de pagamento de link **não promovido** é ignorado; o de link promovido concilia a devolução do pedido (`reconcileRefunds`).
- E-mail do pagador no Mercado Pago: `PAY_LINK_PAYER_EMAIL`; se vazio, `pagamentos@<domínio de SITE_URL, sem "www.">`.
- A cada 6 h o job `tick` confere no Mercado Pago os links com Pix gerado e ainda não pagos (últimos 2 dias), caso um webhook tenha se perdido.
- **Promoção:** quando o pagamento é confirmado, na mesma transação que grava `paid_at`, o pedido registrado à mão vira também um pedido do app (`shop_orders`, ligado por `order_pay_links.shop_order_id`), para valer as mesmas regras de cancelamento, devolução e avisos.
  - Cliente **convidado**: `customers.guest = true`, e-mail `link-<linkId>@convidado.invalid`, sem senha nem Google (não consegue entrar). A página do link, com o token, é a área dele.
  - `shop_orders.manual = true` (os apps ignoram o campo). Uma única linha de item, `"<qtd> × <produto>"`, com qty 1 e `unit_price` = valor pago; `total` = valor pago.
  - Pedido já cancelado quando o pagamento chega: é promovido já cancelado (`cancelled_by = 'loja'`) e devolvido automaticamente.
  - Pedido já "pago" à mão e Pix do link: é promovido e a confeiteira recebe o aviso "pagamento em dobro"; não há devolução automática (ela escolhe qual devolver pelo card).
  - Links pagos antes desta funcionalidade são promovidos em silêncio (sem push nem devolução automática) pela conciliação de 6 h (`reconcileLinks`, chamada por `jobs/tick`, não por `reconcilePending`) e ao abrir a página do link.
  - Varredura em `reconcileLinks`: pedido manual pago e cancelado pela loja, com `refunded_total = 0` e sem nenhuma linha em `refunds`, é devolvido. Vale só para pagamento novo (link antigo promovido depois fica de fora).
  - O convidado usa o e-mail técnico `link-<id>@convidado.invalid`; `isEmail` (cadastro, login) rejeita qualquer e-mail terminado em `.invalid` para ninguém ocupar esse e-mail antes do pagamento.
  - Link antigo já entregue: a janela de devolução (`delivered_at`) conta do pagamento antigo (`paid_at` do link), não da promoção.
- A prévia do link (WhatsApp etc.) só faz `GET`, que nunca gera Pix; o Pix só nasce no `POST …/pix`.

## Preços, fornecedores e alertas de margem

Todas exigem sessão da confeiteira.

| Rota | O que faz |
|---|---|
| `GET/POST /api/suppliers` · `PATCH/DELETE /api/suppliers/:id` | Fornecedores: `{name, kind: "site"\|"loja", url, address}`. Site só com https público (recusa localhost, IP, rede interna). Lojas da plataforma Tray ganham `scraper: "tray"` (busca de preços) sozinhas |
| `GET /api/ingredients/:id/prices` | Última cotação de cada fornecedor (mais barata primeiro, por kg/L/un) e histórico |
| `POST /api/prices/search` | `{ingredient_id, term?}`: busca nos sites com busca. Um site com erro vem em `errors`, sem derrubar a busca. Cache de 12 h, 1 requisição/s por loja |
| `POST /api/prices/link` | Liga um produto do site ao ingrediente (`{ingredient_id, supplier_id, name, url, price, size, unit}`); passa a ser conferido todo dia |
| `POST /api/prices/quote` | Cotação manual: `{ingredient_id, supplier_id, price, pack_size}` |
| `POST /api/ingredients/:id/use-price` | `{quote_id}`: passa preço e tamanho da cotação para o ingrediente e dispara os alertas |
| `GET /api/price-alerts` · `POST /api/price-alerts/:id` · `POST /api/price-alerts/:id/apply` | Alertas não lidos; dispensar; "aplicar preço sugerido" (fixa `sale_price` da receita) |

- **Preço do cardápio fixado** (`recipes.sale_price`, opcional): vale no lugar do calculado em todo o sistema (`pricing.rounded` já vem com ele; `suggested` continua sendo o do cálculo). Margem mínima em `settings.min_margin_pct` (padrão 30).
- **Alertas** (`price_alerts`): toda mudança de `package_cost`/`package_size` (edição, nota fiscal, "usar este preço") grava no histórico e compara cada receita que usa o ingrediente. Com preço fixado: avisa se a margem real cair abaixo do mínimo ou perder 5 pontos. Sem preço fixado: avisa se o preço sugerido subir 5% ou mais.
- **Atualização diária** (job, 04h de Brasília): relê os produtos ligados e grava cotação nova quando o preço do site mudou; **não** altera o preço do ingrediente sozinha.
- Scraper: `backend/lib/scrapers/tray.ts` (lê `data-ga4-name`/`data-ga4-price` da busca; respeita o robots.txt). Se o site mudar, o fornecedor aparece como "precisa de revisão" com o erro.

## Engajamento dos clientes (medição anônima)

O site e o app dos clientes enviam eventos anônimos; a confeiteira lê os números na gestão (web e app). **Nada identifica a pessoa:** só um `session_id` aleatório que dura uma visita (sem código fixo por aparelho, sem conta, sem IP gravado, sem conteúdo de campos). Eventos brutos ficam 90 dias; o agregado diário, 13 meses. Plano: `docs/superpowers/plans/2026-10-13-engajamento-clientes.md`.

| Rota | O que faz |
|---|---|
| `POST /api/medicao/eventos` (público) | Lote de até 20 eventos: `{session_id, platform ("site"/"android"/"ios"), app_version, events: [{name, at (ms desde 1970), route?, item_id?, detail?, qty?}]}`. Responde `204` sempre que o formato do lote é válido (evento inválido é descartado em silêncio); `400` se `session_id`/`platform`/`events` estiverem errados; `429` acima de 120 lotes/10 min por IP. Vista e clique do mesmo item contam uma vez por sessão. |
| `GET /api/engagement/overview` | `current` e `previous` (período anterior do mesmo tamanho): `sessions`, `page_views`, `orders`, `paid_orders`, `revenue`, `conversion_pct`. Pedidos e receita vêm das tabelas de pedidos. |
| `GET /api/engagement/funnel` | `steps` (Entrou → Clicou em um item → Adicionou ao pedido → Preencheu os dados → Escolheu a entrega → Chegou ao pagamento → Enviou o pedido → Pedido feito) com `sessions`, `pct_of_top`, `pct_of_previous`, e `biggest_drop` (`after`, `lost`). |
| `GET /api/engagement/recipes?sort=` | Por receita/combo: `views`, `clicks`, `adds`, `orders`, `revenue`, `click_rate_pct`, `order_rate_pct`. `sort`: `views`, `clicks` (padrão), `adds`, `orders`, `revenue`. |
| `GET /api/engagement/pages` | Por página/tela: `views` e `sessions`. |
| `GET /api/engagement/sources` | `referrers` (origem das entradas) e `platforms` (sessões por plataforma). |

**Desligada por padrão.** A coleta só envia eventos quando o build a liga: site → variável `NEXT_PUBLIC_ENGAGEMENT_ENABLED=true` (Vercel; não envia nos previews `*.vercel.app`); app do cliente → `--dart-define=ENGAGEMENT_ENABLED=true`. Ligar só depois de a Política de Privacidade nova estar no ar e revisada. Quem não quer ser medido usa "Não medir meu uso" (rodapé do site, `localStorage` `rk_no_track`) ou o interruptor em Meus dados (app); *Do Not Track* e *Global Privacy Control* também desligam.

As rotas de leitura são só da dona e aceitam `from`, `to` (`AAAA-MM-DD`; padrão: últimos 30 dias; máximo 400 dias) e `platform` (opcional). O agregado é refeito a cada 5 minutos.

Eventos (lista fechada): `screen_view` (`route`, `detail` = origem: `direct`, `instagram`, `whatsapp`, `search`, `other`), `menu_item_view`/`menu_item_click` (`item_id` = id da receita ou `combo-<id>`, `detail` = `landing`/`cardapio`/`home_app`), `cart_add`/`cart_remove` (`item_id`, `qty` 1 a 99), `checkout_step` (`detail`: `items`, `data`, `delivery`, `payment`, `extras`), `payment_method_select` e `order_placed` (`detail`: `pix`, `cartao`, `dinheiro`), `coupon_attempt` (`ok`, `invalid`, `min_total`, `inactive`, `used_up`; nunca o código), `checkout_submit` (`ok`, `validation`, `card_refused`, `network`, `other`), `card_3ds` (`authenticated`, `closed`, `failed`).
