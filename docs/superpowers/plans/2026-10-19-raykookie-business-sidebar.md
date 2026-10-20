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
| Planejamento e finanças | Metas, Custos e ajustes, Prospecção |

No app, a tela "Mais" mostra as mesmas seções, sem as abas que já estão na barra inferior (Início, Estoque, Receitas, Pedidos), e acrescenta os itens que só existem no celular: Venda Presencial e Som de novos pedidos (em Vendas e pedidos) e Histórico (em Planejamento e finanças).

## Onde fica cada coisa

- Web: `frontend/lib/nav.ts` (árvore, única fonte), `frontend/components/NavTree.tsx` (sidebar e gaveta), `frontend/components/Shell.tsx` (usa os dois). URLs inalteradas.
- App: `mobile-server/lib/features/admin/menu_tree.dart` (árvore + widget recolhível), usada por `more_screen.dart`.
- Logo: `scripts/gerar-logo-business.py` gera `raykookie-business-*.svg` (web e app) e a fonte `FrauncesItalic.ttf`. Os SVGs oficiais não foram tocados.
- Dourado (tokens): `--gold-1/2/3` em `frontend/app/globals.css`, `AppColors.goldOnDark/goldOnLight` em `mobile-common/lib/ui/theme.dart`.

## Novas abas
Acrescente o item em `NAV_SECTIONS` (`frontend/lib/nav.ts`) e em `menuSections` (`mobile-server/lib/features/admin/menu_tree.dart`), na seção certa, e a rota em `GESTAO_ROUTES` (`frontend/middleware.ts`). A Prospecção já entrou assim, em Planejamento e finanças (o grupo "Outros" da PR #75 foi absorvido pela árvore).
