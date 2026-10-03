# SDD ledger — plan: docs/superpowers/plans/2026-10-03-link-pagamento-paridade.md
Spec: docs/superpowers/specs/2026-10-03-link-pagamento-paridade-design.md
RETOMADA: ler este arquivo; tarefas com "complete" estão prontas; recomeçar na primeira sem "complete".
Ambiente: TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test (subir: pg_ctl -D ~/works/.rayka-pgtest ... start, ver ledger anterior em docs/superpowers/plans/2026-10-03-link-pagamento.ledger.md); recriar o banco de teste antes de cada suíte: bash ~/works/rayka-testdb-reset.sh.
Git sombra: bash ~/works/rayka-sgit.sh commit|pkg BASE HEAD. Briefs: ~/works/rayka-sdd-brief-paridade.sh N. Flutter: export PATH="$HOME/development/flutter/bin:$PATH". NUNCA tocar na produção (porta 10005, banco rayka_estoque, builds sem NEXT_DIST_DIR=.next-test).
BASE do trabalho: 8f595ed
Task 0: complete (baseline backend 228/228, tsc backend/frontend ok)
Task 1: implementer DONE_WITH_CONCERNS (8f595ed..875a239; 237/237 confirmado pelo controlador)
Ruling: INSERT do shop_orders no plano passava cancel_reason NULL para pedido não cancelado, mas a coluna é NOT NULL DEFAULT '' — usa '' — custo se errado: nenhum.
Nota de ambiente: o shell é fish: usar `env TEST_DATABASE_URL=... npm test` (export estilo bash não vale e os testes de integração são pulados em silêncio).
Task 1: review — Needs fixes (2 Important plan-mandated: (1) promoção de link ANTIGO dispara falso "pagamento em dobro" e devolução automática; (2) efeitos pós-commit não isolados nem recuperáveis)
Ruling: (1) promoteLink (links já pagos) NÃO notifica nem devolve sozinho (flag legacy): o pagamento antigo já foi avisado pelo fluxo anterior e a devolução, se havia, foi decidida no painel do MP — custo: pedido antigo cancelado e pago não ganha estorno automático (a confeiteira vê no card). (2) afterPromotion isola cada passo com try/catch+console.error, na ordem devolução → entrega → push, e reconcileLinks varre manual+cancelado+pago sem nenhuma devolução e chama refundLatePayment — custo: a varredura roda a cada 6 h. Inclui 3 Minor baratos: trava de orders primeiro em promoteLink, console.error no catch de reconcileLinks, try separado por conciliação no tick.
Task 1: fix round 1/5 implementado (875a239..608bf24; 239/239 confirmado). Desvio aceito: sweep com JOIN order_pay_links ON l.paid_at = so.paid_at (distingue pagamento novo de promoção legada)
Task 1: minor (deferred): `n` da varredura só conta quando refundLatePayment devolve truthy (cosmético)
Task 1: minor (deferred): `qtyLabel` sem separador de milhar/arredondamento (ex.: 1500 → "1500")
Task 1: observação: link ANTIGO pago de pedido cancelado não ganha estorno automático (ruling); fica para a confeiteira
Task 1: complete (commits 8f595ed..608bf24, review clean após 1 rodada)
Task 2: implementer DONE (608bf24..c567b18; 241/241 confirmado)
Task 2: minor (deferred): refund-guard antes do ramo approved: pagamento approved com reembolso parcial de link ainda NÃO promovido (aprovação original perdida) é ignorado no webhook (conciliação de 6 h cobre) — decidir na revisão final
Task 2: minor (deferred): faltam testes de estorno com id de outro pagamento do mesmo link; `void order;` sobrando
Task 2: complete (commits 608bf24..c567b18, review clean)
Task 3: implementer DONE_WITH_CONCERNS (c567b18..dc433c8; 246/246 confirmado)
Ruling: teste do plano usava reason_code 'desisti', que não existe em CANCEL_REASONS (a rota segue o app: código desconhecido vira 'outro' e exige motivo) — trocado por 'nao_preciso' no teste; rota idêntica à do app — custo: nenhum.
Task 3: minor (deferred): promoteLink em link pago sem paid_mp_id abre transação a cada poll (raro/inexistente)
Task 3: minor (deferred): GET payLinkView promove link antigo (efeito colateral, uma vez por link, silencioso) — comentar no código
Task 3: minor (deferred): custo por poll de link pago (4 queries); upload multipart sem teto de Content-Length antes do formData() (igual ao app, agora sem login; precisa de token pago) — TRIAR na revisão final (endurecimento barato)
Task 3: minor (deferred): faltam testes de devolução com token de outro link e do caminho 500
Task 3: complete (commits c567b18..dc433c8, review clean)
Task 4: implementer DONE (dc433c8..fb970eb; 247/247 confirmado)
Task 4: minor (deferred): título do teste cita "clientes novos" do painel mas não verifica (ajustar título ou asserir) — arrumar na revisão final
Task 4: minor (deferred): rejects.toThrow() genérico (poderia checar 23505)
Task 4: complete (commits dc433c8..fb970eb, review clean)
Task 5: implementer DONE (fb970eb..9344ab3; tsc fe+be ok; build de teste lista /pagar/[token] e /devolucao)
Task 5: ⚠️ resolvido pelo controlador: pedido promovido está sempre pago e `getCustomerOrders` só devolve `pix` quando paid_at é nulo → PixCopy nunca aparece no cartão do link
Task 5: minor (deferred, TRIAR): token codificado de forma inconsistente (orderLinks com encodeURIComponent; página de devolução com token cru) — inofensivo p/ tokens base64url
Task 5: minor (deferred, TRIAR): resumo do link repete informação acima do cartão depois de pago (cosmético)
Task 5: complete (commits fb970eb..9344ab3, review clean)
Task 6: implementer DONE (9344ab3..0b543a8; backend 247/247, fe tsc ok, flutter 30/61/60)
Nota: produção reiniciada às 15:55 (systemd; provavelmente o `install.sh` do usuário) com o build de 15:26/27 e banco em 017; migration 018 e código da paridade ainda NÃO estão em produção.
Task 6: minor (deferred): API.md:221 descreve a varredura de forma ampla demais (falta "só pagamento novo; link antigo promovido depois fica de fora" e refunded_total=0) — corrigir na rodada final
Task 6: minor (deferred): API.md:194 linha do GET sem o 404 de token inválido (opcional)
Task 6: complete (commits 9344ab3..0b543a8, review clean)
FINAL REVIEW: em andamento (8f595ed..0b543a8)
FINAL REVIEW (opus): Ready after fixes — 2 Important: (1) cadastro aceita link-<N>@convidado.invalid → promoção estoura na unicidade, transação desfaz e o pagamento nunca é registrado; (2) "Devolver valor" do valor todo (pagamento em dobro ou sinal) encerra o pedido pago em dinheiro (closeIfFullyRefunded em ownerRefund).
Ruling: rodada única de correção: (1) `isEmail` (shared) rejeita domínio `.invalid` — cobre cadastro e qualquer outro caminho; Google não devolve .invalid; (2) `ownerRefund` NÃO chama closeIfFullyRefunded quando `shop_orders.manual` (cancelar de verdade continua em "Cancelar e devolver o Pix"; aprovação de devolução pedida pelo cliente continua encerrando) e o texto do push "em dobro" aponta para "Devolver valor" — custo: pedido manual totalmente devolvido por "Devolver valor" fica aberto até a confeiteira cancelar. Inclui Minors: legacy não reabre janela de 48h (delivered_at = paid_at do link), PATCH não cancela linha de pedido do app por corrida (guarda no UPDATE), edição de data/nome do pedido manual propaga para shop_orders, página do link esconde resumo/aviso quando há shop_order, teto de Content-Length (12 MB) no readRefundForm, testes faltantes (getOrders do promovido, promoção pelo "Gerar novo Pix"), título do teste de isolamento, API.md da varredura.
Observação operacional p/ o relatório: pedido promovido em "novo" permite ao cliente cancelar e receber o Pix de volta sem aprovação (paridade com o app); a confeiteira deve mover o pedido para "Em produção" quando começar.
FINAL FIX: implementer DONE_WITH_CONCERNS (0b543a8..bbbb3c5; backend 256/256 confirmado; F4 race só testada no caminho comum)
FINAL FIX re-review (opus): F1–F8 ADDRESSED, mas a guarda do F4 introduziu REGRESSÃO N1 (PATCH só de pagamento em pedido do app/promovido: $3 NULL → NOT(true AND NULL) = NULL → 409 USE_CANCEL enganoso; afeta site e app de gestão) + N2 (pedido manual 100% devolvido por "Devolver valor" fica aberto e não pode mais ser cancelado) + N3 (guarda sem teste). Não publicável com N1.
Ruling: abrir EXCEÇÃO à regra de "uma só rodada de correção final": N1 é regressão introduzida pela própria rodada e quebra fluxo diário (marcar "pago"); corrigir com `$3::text IS NOT DISTINCT FROM 'cancelado'` + teste de PATCH só de pagamento (200 e salvo) em linha de pedido do app/promovido; N2: em cancelCore, pedido `manual` já 100% devolvido e aberto pode ser cancelado SEM nova devolução (fecha itens e marca cancelamento da loja) — custo: mudança pequena em cancelCore; N3: teste cobre o caminho comum. Depois: re-revisão curta só desse diff.
FINAL FIX 2: implementer DONE (bbbb3c5..624afb0; backend 260/260 confirmado)
FINAL FIX 2 re-review (opus): N1, N2, N3 ADDRESSED; Ready to publish: yes. Nits: teste do N2 não asserta cancelled_at; cancelamento de manual com devolução em andamento (committed>0) mostra cancelado antes da confirmação do Pix (seguro, sem nova devolução)
