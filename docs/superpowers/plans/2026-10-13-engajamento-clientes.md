# Engajamento dos clientes: observabilidade nos apps de cliente, leitura na gestão

Plano (não implementado). Escrito em 2026-10-13.

## 1. O que a dona quer saber

Perguntas que a gestão (web e app) precisa responder, em qualquer período (7, 30, 90 dias) e por plataforma:

1. **Quais receitas mais chamam atenção?** Quantas vezes cada produto foi visto, clicado, colocado no pedido e realmente pedido.
2. **Quais páginas e telas as pessoas acessam?** Visitas, visitantes únicos, de onde entram e onde saem.
3. **Até onde as pessoas vão na compra e onde param?** Funil do pedido, com a perda entre cada passo.
4. **Quem volta?** Visitantes que retornam, sessões por visitante, site contra app.

Não é objetivo: publicidade, remarketing, perfil individual de comportamento, nem enviar dado a terceiros.

## 2. Ponto de partida (o que o código já tem)

- **Site** (Next, `frontend/`): landing, `/cardapio`, `/cliente/pedidos/novo` (formulário em 5 passos: itens, dados, recebimento, pagamento, extras) e as telas da conta. **App do cliente** (Flutter, `mobile-client/`) tem as mesmas rotas (`router.dart`).
- **Backend** (Java, módulos por domínio): `orders` (pedidos, cupons), `payments`, `insights` (estatísticas do mês), `scheduling` (jobs), `ratelimit` (contadores no Postgres), `security` (`AccessLevel`).
- **Banco:** migrations em `backend-java/platform/persistence/.../db/migration/{production,platform}`; a última é `V28__coupons.sql`, então a próxima é a **V29**.
- **Não existe nenhum registro de uso hoje** além dos pedidos e pagamentos (a verdade do funil final já está em `shop_orders`).
- **Atenção, restrição legal:** a Política de Privacidade (`shared/legal.ts`) afirma hoje *"Não usamos cookies nem ferramentas de publicidade ou de rastreamento de comportamento"*. Medir cliques e páginas **muda essa frase**. A política (servida ao site e aos apps) tem que ser atualizada **antes** de a coleta ir para produção.

## 3. Decisões de desenho

| Tema | Decisão proposta | Por quê |
|---|---|---|
| Quem coleta | **Própria (first-party)**: nosso endpoint e nosso banco. Sem Google Analytics, Firebase Analytics, Meta Pixel etc. | Mantém a promessa de não compartilhar com terceiros; os dados já estão onde a gestão lê. |
| Identificação | **Anônima de verdade:** só `session_id` aleatório, guardado em `sessionStorage` (morre ao fechar a aba; no app, na memória). **Não há código fixo por aparelho**, nem `customer_id` (decisão da dona, §9.1). | Sem identificador que atravesse visitas, o dado não liga a ninguém e não precisa de consentimento. |
| O que **não** se guarda | IP, user agent completo, e-mail, nome, telefone, endereço, CPF, código de cupom digitado, conteúdo de campos. | O IP serve só ao limite de taxa e não é gravado. |
| Verdade do funil final | **Servidor.** `order_created` e `payment_approved` saem do que já existe (`shop_orders`, pagamentos), não do cliente. | Cliente perde evento (aba fechada, sem rede); pedido e pagamento não podem divergir do relatório. |
| Parte de cima do funil e passos | **Cliente** (`track()`), em lote. | É o único lugar onde se vê "abriu e não pediu". |
| Retenção | Eventos brutos **90 dias**; **agregados diários** guardados por mais tempo (13 meses). | Responde às perguntas sem acumular dado bruto. |
| Consentimento | **Sem aviso de cookies.** Como nada identifica a pessoa nem persiste entre visitas, não há dado pessoal. A transparência vem da Política (texto novo, §10 PR 5) e do interruptor "não medir" em Meus dados e no rodapé do site. | Decisão da dona (§9.2); o aviso só seria necessário com identificador persistente. |

## 4. Eventos

Nomes fixos (lista fechada no servidor; evento desconhecido é descartado). Todo evento leva: `name`, `occurred_at`, `session_id`, `platform` (`site`, `android`, `ios`), `app_version` e `props` pequeno (JSON, só as chaves abaixo).

| Evento | Quando | `props` |
|---|---|---|
| `screen_view` | Troca de página/tela | `route` (sem parâmetros pessoais; `/cliente/pedidos/123` vira `/cliente/pedidos/:id`), `referrer_kind` (`direct`, `instagram`, `whatsapp`, `search`, `other`, só nas entradas) |
| `menu_item_view` | Produto ou combo aparece na tela do cardápio (uma vez por sessão e item) | `item_id` (`recipe:<id>` ou `combo:<id>`), `surface` (`landing`, `cardapio`, `home_app`) |
| `menu_item_click` | Toque em "Pedir" ou no cartão do produto | `item_id`, `surface` |
| `cart_add` / `cart_remove` | Mudou a quantidade no passo 1 do pedido | `item_id`, `qty` |
| `checkout_step` | Chegou a um passo do pedido | `step` (`items`, `data`, `delivery`, `payment`, `extras`) |
| `payment_method_select` | Escolheu Pix, cartão ou dinheiro | `method` |
| `coupon_attempt` | Aplicou cupom | `ok` (booleano), `reason` (`invalid`, `min_total`, `inactive`, `used_up`) — **sem o código** |
| `checkout_submit` | Apertou "Finalizar pedido" | `ok`, `error_kind` (validação, cartão recusado, erro de rede…) |
| `card_3ds` | Abriu e fechou o desafio 3DS | `result` (`authenticated`, `closed`, `failed`) |

Gerados no servidor (sem passar pelo cliente): `order_created`, `payment_approved`, `payment_refused`, `order_cancelled`, com o mesmo `session_id` quando o cliente o enviou no pedido (campo opcional novo no corpo de `POST /api/cliente/pedidos`), para ligar a sessão ao desfecho.

**Funil** (sessões únicas que alcançaram o passo): `screen_view` do cardápio → `menu_item_click` → `checkout_step=items` com `cart_add` → `data` → `delivery` → `payment` → `checkout_submit ok` → `order_created` → `payment_approved`. A perda é calculada entre passos consecutivos, e "parou em" = último passo da sessão.

## 5. Coleta (clientes)

- **Opção de não medir:** `rk_no_track` em `localStorage` (guarda só a recusa, nenhum identificador); com ela, `track()` não faz nada. Interruptor no rodapé do site e em Meus dados; o app respeita a mesma preferência. O navegador com *Do Not Track* / *Global Privacy Control* também desliga a medição.
- **Site:** módulo `frontend/lib/track.ts` com `track(name, props)`: fila na memória, envio em lote (até 20 eventos ou 5 s), `navigator.sendBeacon` ao sair da aba; `session_id` em `sessionStorage`, novo após 30 min parado. Chamado nos pontos do §4 (`CardFields` e o formulário de pedido já têm os passos). Não envia nada no `localhost` nem nos previews.
- **App:** classe `Analytics` em `mobile-common` (usada por `mobile-client`): mesma fila, persistida em disco se estiver sem rede (limite de 200 eventos); sem identificador fixo. Telas registradas pelo `GoRouter` (um observer), itens e passos como no site.
- **Caminho:** `POST /api/medicao/eventos`, pelo mesmo domínio (o rewrite `/api` já existe), com nome **neutro** (evita bloqueio por listas de anúncios; não usar `analytics` nem `track` no caminho).
- **Mesmo evento nos dois lados:** o catálogo de nomes e chaves vive em `shared/` (TypeScript) e em `mobile-common` (Dart), com um teste que compara os dois, no modelo que o projeto já usa para regras (G25).

## 6. Backend

**Módulo novo `engagement`** (mesma estrutura hexagonal dos outros; as regras do ArchUnit se aplicam).

- **Entrada:** `POST /api/medicao/eventos`, **público** (novo nível de acesso `PUBLIC_WRITE`, entra na matriz de acesso do projeto). Lote de até 20 eventos e 8 KB; nomes e chaves conferidos contra a lista fechada; `occurred_at` aceito só dentro de ±24 h; **limite por IP** com o `RateLimiter` que já existe (o IP não é gravado); bots óbvios (`User-Agent` de rastreador) são descartados. Responde 204 sempre que o formato é válido (o cliente nunca trava por causa disso).
- **Tabelas (V29):**
  - `engagement_events(id, occurred_at, session_id uuid, platform, app_version, name, route, item_id, props jsonb)`, índices em `(name, occurred_at)` e `(session_id, occurred_at)`.
  - `engagement_daily(day, platform, name, route, item_id, step, events, sessions)`: agregado diário que a gestão consulta (rápido, sobrevive ao descarte do bruto).
- **Jobs** (módulo `scheduling`): recalcula o agregado do dia a cada hora e fecha o dia anterior de madrugada; apaga eventos brutos com mais de 90 dias.
- **Privacidade:** como os eventos não ligam a nenhuma conta, excluir a conta e a "Cópia dos dados" não os alcançam (e a política diz isso).
- **Eventos do servidor:** o módulo escuta os eventos de domínio que já existem (pedido criado, pagamento aprovado/recusado, cancelado) e grava os `order_*`/`payment_*` correspondentes.

## 7. Leitura na gestão (API só da dona, `OWNER`)

Todas aceitam `from`, `to` (padrão últimos 30 dias) e `platform` (opcional).

| Rota | Devolve |
|---|---|
| `GET /api/engagement/overview` | Sessões, pedidos, conversão (pedidos ÷ sessões), tudo com comparação com o período anterior. |
| `GET /api/engagement/funnel` | Cada passo do funil com sessões, % do topo e % do passo anterior, mais "onde mais param". |
| `GET /api/engagement/recipes` | Por produto/combo: vistas, cliques, adicionados, pedidos, receita, taxa clique→pedido. Ordenável. |
| `GET /api/engagement/pages` | Por rota: visualizações, visitantes, entradas, saídas. |
| `GET /api/engagement/sources` | Origem das entradas (`referrer_kind`) e plataforma. |

Os números de pedido e receita vêm das tabelas de pedidos (não dos eventos), para bater com Pedidos e Dashboard.

## 8. Telas

- **Gestão web:** página **Engajamento** (`/engajamento`; entra na lista `GESTAO_ROUTES` do middleware): seletor de período e plataforma, cartões de resumo, **funil** em barras horizontais com a perda entre passos, **ranking de receitas** (tabela ordenável) e **páginas mais acessadas**. Gráficos simples em SVG/CSS (sem dependência nova), seguindo o kit de componentes do painel.
- **Gestão mobile** (`mobile-server`): tela **Mais → Engajamento** com os mesmos quatro blocos, consumindo as mesmas rotas; cálculo nenhum no cliente.
- **Clientes:** sem aviso de entrada; interruptor "Ajudar a melhorar o cardápio (medir meu uso)" em Meus dados e link "Não medir" no rodapé do site.

## 9. Decisões da dona

1. **`customer_id`: decidido.** Só anônimo. A coluna não será criada nesta fase.
2. **Consentimento: decidido (revisto).** Sem aviso de cookies, porque a medição é anônima (sem código fixo por aparelho). Se um dia se quiser medir retorno de visitantes, aí volta o aviso com aceite.
3. **Retenção: decidido.** 90 dias de bruto e 13 meses de agregado.
4. **Revisão do texto da Política e dos Termos: decidido.** A própria dona revisa antes do PR 5.

**Consequência:** sem código fixo, "visitantes únicos" vira **sessões** (e visitantes aproximados por sessão), e a **taxa de retorno** deixa de existir. Funil, receitas, páginas e origem não mudam.

## 10. Entregas (PRs, nesta ordem)

1. **Backend: ingestão, tabelas e agregado.** Módulo `engagement`, V29, endpoint de coleta com limite de taxa, jobs, testes (unitários do agregado e do funil com massa fixa; IT de ingestão; contrato; matriz de acesso; ArchUnit). Sem efeito para quem usa: ninguém envia evento ainda.
2. **Coleta nos clientes.** `track.ts` e `Analytics` (Dart) + instrumentação das telas e do pedido + teste de paridade do catálogo + testes de widget/unidade. Atrás de uma chave (`MP`-style, `ENGAGEMENT_ENABLED`) desligada por padrão.
3. **Rotas de leitura e página Engajamento no gestão web.**
4. **Tela de Engajamento no gestão mobile.**
5. **Privacidade e produção:** texto novo da política (revisado pela dona), interruptor em Meus dados, exclusão de conta limpando eventos, retenção, e só então ligar `ENGAGEMENT_ENABLED`.

Cada PR sai com `tsc`/build ou `verify` verde e verificação no navegador/aparelho (o que foi e o que não foi testado, como nos PRs anteriores).

## 11. Riscos

- **Bloqueadores de anúncio** podem esconder parte do site; por isso o caminho é neutro, mas parte do tráfego pode sumir. Os números de pedido e receita não dependem disso.
- **Custo e latência no Vercel:** cada lote passa pelo rewrite `/api`; lote de até 20 eventos mantém o volume baixo.
- **Dupla contagem site + app** para a mesma pessoa: aceitável, pois os visitantes são por aparelho; a tela mostra por plataforma.
- **Cardinalidade:** `route` sempre normalizada e `item_id` só de produtos do cardápio.
- **Janela de privacidade:** não ligar em produção antes do PR 5 (texto e interruptor).
