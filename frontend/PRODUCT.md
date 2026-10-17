# Product

<!-- impeccable:product-schema 1 -->

<!-- Fonte: briefing do dono no pedido de redesign (sem rodada de entrevista); itens "em aberto" ficam marcados. -->

## Platform

web

## Users
Gente que quer presentear, comemorar ou pedir o doce da semana. Quase todo acesso é pelo celular, vindo do Instagram (@raykookies). Decidem rápido: veem fotos, escolhem um item ou combo, tocam em pedir.

## Product Purpose
Site público da Raykookie, confeitaria artesanal pequena que trabalha só sob encomenda (pedido com 1 dia de antecedência): cookies, brownies, bolos, docinhos. O site apresenta a casa, mostra o cardápio vivo (vem do painel) e leva ao pedido. Sucesso = a pessoa toca em "Fazer pedido" (/cliente) ou em "Pedir" num item (/cliente/pedidos/novo?item=…&from=home).

## Positioning
Fornadas pequenas, feitas à mão, só sob encomenda: tudo chega fresco. Nasceu na cozinha de casa com uma receita de cookie.

## Operating Context
Cardápio, fotos (/api/fotos/…) e releases do app vêm do backend. Pedido, login e pagamento (Pix, cartão, dinheiro) ficam em /cliente. O site público também hospeda Termos, Privacidade, Excluir conta e o cardápio compartilhável (/cardapio). Entrada pelo Google exige que a página explique a finalidade do app e o uso do login (verificação do Google): esse texto não pode sumir.

## Capabilities and Constraints
- Rotas, links e parâmetros de pedido não mudam. Tema de navegador #d42a76, metatags/OG/canonical e verificação do Search Console ficam.
- Feedback com nota e até 5 fotos exige cliente logado (/api/cliente/feedback).
- Dados do cardápio (categorias, tags, fotos, nomes) pertencem ao dono; o redesign só apresenta.
- Estilos compartilhados (.brand-scope, Button, Input) servem /cliente: não alterar.

## Brand Commitments
Logo e rosa são fixos: use os SVGs de public/brand (não redesenhar). Rosa da marca #e94b8a (rk-rosa) / theme #d42a76. Tom: caloroso, caseiro, carinhoso, com acabamento profissional.

## Evidence on Hand
Fotos reais em public/fotos (4) e no cardápio (/api/fotos/N; 7 de 13 itens têm foto). Depoimentos no site hoje são texto fixo em código (3), não dados reais: em aberto, a confirmar com o dono. Não inventar clientes, números ou fotos.

## Product Principles
1. O doce é o foco; tudo que é técnico (app, privacidade) fica acessível e quieto.
2. Pedir é sempre o próximo passo óbvio, a um toque, no celular.
3. Fotografia real manda; sem foto, a marca ocupa o lugar com dignidade.
4. Só afirmar o que é verdade (nada de prova social inventada).

## Accessibility & Inclusion
WCAG AA, foco visível, alvos de toque ≥ 44px, prefers-reduced-motion respeitado. Português do Brasil.
