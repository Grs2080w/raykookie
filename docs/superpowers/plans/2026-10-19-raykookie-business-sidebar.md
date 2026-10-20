# raykookie business: sidebar em árvore, novo nome e logo dourada

Branch `worktree-business-sidebar`. Só navegação e identidade visual: nenhuma tela, rota ou regra mudou.

## Árvore de navegação (mesma no web e no app)

| Seção | Abas |
|---|---|
| (solto) | Início |
| Produção e catálogo | Receitas, Cardápio, Categorias |
| Estoque e compras | Estoque, Fornecedores |
| Vendas e pedidos | Pedidos, Cupons, Devoluções, Endereço de retirada |
| Clientes | Feedback, Engajamento, Privacidade dos clientes |
| Planejamento e finanças | Metas, Custos e ajustes · **(Prospecção entra aqui)** |

No app, a tela "Mais" mostra as mesmas seções, sem as abas que já estão na barra inferior (Início, Estoque, Receitas, Pedidos), e acrescenta os itens que só existem no celular: Venda Presencial e Som de novos pedidos (em Vendas e pedidos) e Histórico (em Planejamento e finanças).

## Onde fica cada coisa

- Web: `frontend/lib/nav.ts` (árvore, única fonte), `frontend/components/NavTree.tsx` (sidebar e gaveta), `frontend/components/Shell.tsx` (usa os dois). URLs inalteradas.
- App: `mobile-server/lib/features/admin/menu_tree.dart` (árvore + widget recolhível), usada por `more_screen.dart`.
- Logo: `scripts/gerar-logo-business.py` gera `raykookie-business-*.svg` (web e app) e a fonte `FrauncesItalic.ttf`. Os SVGs oficiais não foram tocados.
- Dourado (tokens): `--gold-1/2/3` em `frontend/app/globals.css`, `AppColors.goldOnDark/goldOnLight` em `mobile-common/lib/ui/theme.dart`.

## Ao fazer o merge com a Prospecção

1. `frontend/lib/nav.ts`: acrescentar o item em `NAV_SECTIONS`, seção `planejamento`, depois de Custos e ajustes (há um comentário marcando o lugar). A Prospecção da outra branch provavelmente editou o array `NAV` do `Shell.tsx`: esse array não existe mais aqui, então resolva o conflito descartando o lado do `Shell.tsx` e movendo o item para o `nav.ts`.
2. `mobile-server/lib/features/admin/menu_tree.dart`: mesmo item em `menuSections`, seção `planejamento` (também comentado). Se a Prospecção também mexeu em `more_screen.dart`, vale o mesmo: a lista de `tile(...)` foi trocada por `MenuTree`.
3. `frontend/middleware.ts` (`GESTAO_ROUTES`) precisa da rota nova, como já era antes.
