# SDD ledger — plan: docs/superpowers/plans/2026-10-03-link-pagamento.md
Spec: docs/superpowers/specs/2026-10-03-link-pagamento-design.md
RETOMADA (se a sessão acabar): ler este arquivo; tarefas com "complete" estão prontas; recomeçar na primeira sem "complete".
Ambiente: TEST_DATABASE_URL=postgres://rayka_test@127.0.0.1:54329/rayka_test (Postgres descartável em ~/works/.rayka-pgtest; subir com
  `/usr/lib/postgresql/17/bin/pg_ctl -D ~/works/.rayka-pgtest -o "-p 54329 -k /tmp -c listen_addresses=127.0.0.1" -l ~/works/.rayka-pgtest/log start`)
Git sombra (commits/diffs): `bash ~/works/rayka-sgit.sh commit "msg" | pkg BASE HEAD | head`. Briefs: `bash ~/works/rayka-sdd-brief.sh N`.
Flutter: export PATH="$HOME/development/flutter/bin:$PATH". NUNCA tocar no banco/serviço de produção (porta 10005, banco rayka_estoque).

Ruling: git sombra em ~/works/.rayka-shadow.git (sem .git dentro do projeto) — o projeto não está em git e a skill exige commits/diffs por tarefa — custo se errado: nenhum no projeto; apagar o diretório desfaz.
Ruling: Postgres de teste descartável na porta 54329 — o role da aplicação não tem CREATEDB e não uso sudo; criar outro banco no servidor de produção está fora de questão — custo: precisa estar de pé para os testes de integração.
Ruling: o backup .tar.gz do Task 0/Step 1 foi substituído pelo commit-base do git sombra (2de59f1; exclui .env, chaves e builds) — custo: .env/chaves não estão no snapshot (não são alterados por este plano).
Ruling: "commit" do plano = commit no git sombra; review package = `rayka-sgit.sh pkg BASE HEAD` — equivalente funcional dos scripts da skill, que exigem git no projeto.

## Varredura prévia (pares de tarefas que dividem arquivo/interface)
| Par | Produz → consome | Achado |
| T1→T3 | `createPixPayment({reference})` | consistente (T3 passa `reference: link:<id>`, `orderId: link.id`) |
| T2→T3 | `pay-link.ts` (T3 troca o bloco de imports do topo e acrescenta no fim); mesmo arquivo de teste (marcador) | consistente: T3 imports ⊇ T2 imports; marcador mantido uma vez |
| T3→T4 | `handleLinkPayment`, `LINK_REF`, `payLinkPix`, `payLinkView`, `ensurePayLink` | assinaturas iguais nos dois textos |
| T2↔T5 | `PayLinkView` definido em `lib/pay-link.ts` (T2) e em `shared/types.ts` (T5) | duplicação de tipo; backend poderia importar de `@shared/types`. Ruling: manter como no plano (mesma forma, sem acoplamento novo) — custo: tipo em dois lugares; unificar depois se divergir |
| T4→T5/T6 | contratos HTTP `/api/pagar/:token`, `/pix`, `/api/orders/:id/pagamento` | consistentes com a spec |
| T5↔T6 | independentes (frontend x Flutter) | — |
| T1..T7 texto interno | testes x código | conferido: arredondamentos (10,005→10; 0,001→recusa), `-5`/`abc` → 400 via `num()`, `ctx({id:'x'})` → 404 |
Varredura sem conflito bloqueante.

Task 0: complete (feita pelo controlador: git sombra 2de59f1, Postgres de teste 54329, migrations 001-016 aplicadas, baseline backend 193/193 verdes, tsc ok)
Task 1: implementer DONE (commit 2de59f1..03c9b34, 195/195 testes); revisão em andamento
Task 1: minor (deferred): teste do link usa orderId 5 = link:5 (não distingue id do pedido da referência; plan-mandated)
Task 1: minor (deferred): reference.replace(':','-') só troca o primeiro ':' (ok para link:<id>)
Task 1: complete (commits 2de59f1..03c9b34, review clean)
Task 2: implementer DONE_WITH_CONCERNS (commit 03c9b34..5cab1ca)
Ruling: seed() do teste da Task 2 usava public_code = 30000+n, que repete entre execuções no banco de teste persistente (UNIQUE) — corrigido para `20000 + Math.floor(Math.random()*70000) + n` (padrão de orders-route.integration.test.ts; respeita CHECK 1000..99999), NÃO a proposta Date.now() do implementador (estouraria a faixa) — custo se errado: colisão aleatória rara (n≤~30 em 70000 valores); exige rodar a suíte 2×. Tasks 3/4 reaproveitam seed().
Task 2: fix (seed public_code aleatório) commit 5cab1ca..e6ea8da
Ruling: falhas intermitentes em refunds/notifications.integration (duplicate shop_orders_public_code_idx) vêm do banco de teste persistente acumulando pedidos (611 linhas) com códigos sorteados em 20000–90000 por vários arquivos de teste ANTIGOS — não do código novo. Resolvido no ambiente: `bash ~/works/rayka-testdb-reset.sh` recria o banco de teste antes de cada suíte completa (4×203/203 verdes). Testes antigos não foram alterados (fora do escopo) — custo se errado: quem rodar a suíte repetidas vezes sem recriar o banco verá colisões raras; pré-existente.
Task 2: minor (deferred): `prev` lido fora da instrução do upsert — duas edições simultâneas de valor podem pular/duplicar o cancelamento do Pix antigo (o webhook com valor ≥ cobrado cobre) [plan-mandated]
Task 2: minor (deferred): após A→B, mp_payment_id aponta para Pix já cancelado; B→C cancela de novo (inofensivo)
Task 2: minor (deferred): 10,005 arredonda para 10,00 (ponto flutuante); teste só verifica ok [plan-mandated]
Task 2: minor (deferred): troca de valor/cancelamento e 409 por paid_at só testados na Task 3 [plan-mandated]
Task 2: ⚠️ resolvidos pelo controlador: CHECK de orders.payment = (pendente,sinal,pago) em 001_init.sql; cancelPayment coberto na Task 3; falha intermitente = Ruling do banco de teste
Task 2: complete (commits 03c9b34..e6ea8da, review clean)
Task 3: implementer DONE_WITH_CONCERNS (commit e6ea8da..26d88e9; 216/216 em banco recriado; tsc ok)
Ruling: teste "MP desligado: 503" do plano criava o link depois de desligar o MP (ensurePayLink recusa) — link criado antes de desligar — custo se errado: nenhum (código de produção intacto).
Ruling: fakePix do plano gerava ids "p<N>", mas o Mercado Pago usa id numérico e o código compara String(pay.id) — ids numéricos (900000+N) e Number(id) nos testes; a Task 4 deve usar o mesmo (NÃO `.slice(1)`) — custo se errado: nenhum no código de produção.
Task 3: review — Needs fixes (2 Important, plan-mandated)
Ruling: corrigir, contra o texto do plano, (a) payLinkPix: Pix antigo "approved" mas markLinkPaid devolve null → reler o link; se já pago (paid_at ou orders.payment='pago') devolver {state:'paid'} sem gerar Pix novo; senão aviso no log e segue; (b) o try/catch passa a envolver SÓ getPayment/cancelPayment (falha do MP → console.warn e segue, o Pix antigo já venceu); erro de banco em markLinkPaid propaga (rota responde 500, nenhum Pix novo) — a spec manda que a confirmação de pagamento seja confiável; criar 2º Pix cobrável para pedido pago é risco de cobrança dupla — custo se errado: um 500 raro em vez de um Pix novo.
Task 3: minor (deferred): payLinkPix concorrente não é serializado (mp_payment_ids pode duplicar id; pix_expires_at pode ficar mais tarde que o real no MP)
Task 3: minor (deferred): MP cria o Pix e o UPDATE falha → 502 sem contar tentativa; mudar o valor antes de tentar de novo reusa a chave com outro valor
Task 3: minor (deferred): markLinkPaid não confere status do pedido (Pix pago depois de cancelar marca 'pago' em pedido cancelado; sem devolução automática, conforme spec)
Task 3: fix round 1/5 (2 addressed, 0 open — Pix novo para link recém-pago; catch engolindo erro de banco; commits 26d88e9..bf7b78a)
Task 3: minor (deferred): teste (c) só verifica que houve warn, não qual
Task 3: minor (deferred): MP fora do ar ao renovar → Pix antigo não é cancelado (já venceu; o MP recusa pagamento após date_of_expiration) — risco baixo
Task 3: complete (commits e6ea8da..bf7b78a, review clean após 1 rodada)
Task 4: implementer DONE_WITH_CONCERNS (RED parcial: rotas e testes criados juntos; commit bf7b78a..377e38e; 223/223; build de teste ok)
Task 4: minor (deferred): nenhum teste prova que assinatura inválida + ref link: devolve 401 (validSignature mockado true no arquivo); ordem no código está correta
Task 4: minor (deferred): caso "refunded" não espiona reconcileRefunds/refundLatePayment
Task 4: minor (deferred): pagamento por link não manda push para a gestão — fora do escopo pela spec
Task 4: complete (commits bf7b78a..377e38e, review clean)
Ruling: Task 5/Step 7 (conferência E2E no navegador com backend/frontend de dev + MP de mentira) fica para o usuário após publicar, como o próprio plano permite — montar o MP de mentira + sessão custa mais do que verifica além dos testes de backend e do build de teste — custo se errado: um problema visual da página só aparece depois de publicar.
Task 5: implementer DONE (commit 377e38e..15f5eae; tsc ok; build de teste lista /pagar/[token])
Task 5: minor (deferred): backend fora do ar/500 na página vira 404 genérico ("link inválido") em vez de erro
Task 5: minor (deferred): polling continua com a aba oculta; espaçamento mt-3 do PixCopy; erro sem aria-describedby
Task 5: complete (commits 377e38e..15f5eae, review clean)
Task 6: implementer DONE_WITH_CONCERNS (commit 15f5eae..79b3bd7; gestão 57, common 30, cliente 61; analyze com infos pré-existentes + 1 info curly_braces novo em pay_link_sheet.dart:38)
Task 6: minor (deferred): pay_link_sheet.dart `if (...) return null;` sem chaves → 1 info novo do analyzer (plan-mandated)
Task 6: minor (deferred, TRIAR NA REVISÃO FINAL): total com 3ª casa (qtd fracionada, ex. 0,5 × 1,01) — valor pré-preenchido pode ser recusado ou permitir 1 centavo acima do total do backend; comparar com o total arredondado a centavos
Task 6: minor (deferred): segundo toque em "Compartilhar" com a folha do sistema aberta pede o link de novo (idempotente)
Task 6: minor (deferred): Toast de _copy sem checar mounted (inofensivo)
Task 6: complete (commits 15f5eae..79b3bd7, review clean)
Ruling: Task 7/Step 4 — `infra/check.sh --fast` já falha na linha de base porque `flutter analyze` sai com erro em infos PRÉ-EXISTENTES (pickup_screen.dart etc.); a verificação final roda os mesmos passos um a um (tsc+testes backend com banco recriado, tsc frontend, flutter test nos 3 pacotes) e compara o analyze só quanto a problemas NOVOS — custo se errado: o pipeline futuro precisará tratar os infos antigos.
Task 7: implementer DONE (commit 79b3bd7..53cfa8f; backend 223/223, frontend tsc ok, flutter 30/61/57; produção intacta PID 384124, BUILD_IDs 10:29/10:30)
Task 7: minor (deferred): API.md simplifica a resposta do POST …/pix (state paid|active|renewed; erros 404/409/429/502/503) — redação do brief
Task 7: complete (commits 79b3bd7..53cfa8f, review clean)
FINAL REVIEW: em andamento (2de59f1..53cfa8f)
FINAL REVIEW (opus): Ready after fixes — 3 Important: (1) app recusa o valor pré-preenchido com total de meio centavo (qtd fracionada); (2) pagamento aprovado para pedido cancelado/já pago aplicado sem log (lacuna da spec); (3) sem reconciliação dos pagamentos de link se o webhook falhar. Triagem: só T3 "markLinkPaid não confere status" e T6 "total com 3ª casa" viram FIX NOW (=Important 2 e 1); demais KEEP.
Ruling: incluir na única rodada de correção final também 2 Minor ligados a dinheiro — página atualiza quando o Pix/valor muda; payLinkPix recusa (409) link com valor acima do total atual do pedido — baratos e evitam cobrar valor errado — custo se errado: escopo um pouco maior na rodada final.
FINAL FIX: rodada única despachada (F1–F5, ver final-fix-findings.md); base 53cfa8f
FINAL FIX: implementer DONE (53cfa8f..e5cce31; backend 227/227, gestão 58, common 30; reconcileLinks() em pay-link.ts chamada por reconcilePending)
FINAL FIX re-review (opus): F1–F5 ADDRESSED; Ready to publish: yes
Final: parked — arredondamento app×backend diverge em ~1,6% dos totais de meio centavo com qtd fracionada (backend recusa com 400, nunca cobra errado) — Ruling: mantém; correção robusta seria o backend mandar o total arredondado — custo: confeiteira ajusta 1 centavo em casos raros.
Final: parked — reconciliação usa created_at do link (janela de 2 dias); link recompartilhado depois de 2 dias com webhook perdido não é conciliado — Ruling: mantém; trocar para pix_expires_at é o próximo passo — custo: caso raro fica para o "Gerar novo Pix"/painel do MP.
Final: parked — após baixar o preço, o GET ainda mostra o Pix válido do valor antigo (409 só bloqueia gerar novo) — Ruling: mantém; cliente pagaria o valor que já lhe foi cobrado antes — custo: pode receber a mais até o Pix vencer.
Final: parked — erro de banco no SELECT de reconcileLinks pula o laço de devoluções da conciliação (igual ao SELECT de shop_orders) — Ruling: mantém (pré-existente no padrão).
Verificação do controlador: backend 227/227 (banco recriado) + tsc; frontend tsc; Flutter common 30, cliente 61, gestão 58; analyze sem issues em pay_link_sheet.dart; produção intacta (PID 384124, BUILD_IDs 10:29/10:30).
