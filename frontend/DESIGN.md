# Design: site público da Raykookie

Tokens vivem em `app/site.css` (seção 1) sob `.rk-site`. Nada aqui vaza para `/cliente`.

## Direção
Confeitaria artesanal contemporânea: mesa de bancada, massa crua, cacau e o rosa da marca. O doce (a foto) é o protagonista; o resto é moldura quieta. Mobile-first, pensado para quem chega do Instagram com o polegar.

Por quê: o logo é um grotesco pesado preto e rosa com a mordida do cookie, frio e gráfico. O título em serifa quente (Young Serif) dá o lado caseiro; o texto em Figtree dá legibilidade e preço claro. O rosa fica raro e com intenção (ação, mordida, destaque), o resto é creme e chocolate.

## Cor (papéis)
| Papel | Token | Valor | Uso |
|---|---|---|---|
| Fundo | `--cream` | #fbf3ea | página |
| Superfície | `--paper` | #fffaf4 | cartões, formulário |
| Repouso | `--sand` / `--sand-deep` | #f4e6d8 / #e7d3bf | faixas, traço fino |
| Tinta | `--cacau` | #2a160f | texto (15,7:1) |
| Texto secundário | `--cacau-soft` / `--cacau-mute` | #5a4136 / #76604f | 8,5:1 / 5,4:1 |
| Escuro | `--choco` | #3a1d12 | seções escuras |
| Marca | `--rosa` | #e94b8a | preenchimento e decoração (não texto: 3,3:1) |
| Ação | `--rosa-cta` | #d42a76 | botão primário (4,6:1 com texto claro) = theme-color |
| Texto rosa | `--rosa-text` | #a81857 | links e chips (6,5:1) |
| Rosa sobre escuro | `--rosa-on-dark` | #ff82b0 | 6,7:1 sobre chocolate |

## Tipografia
Título: Young Serif 400 (`@fontsource/young-serif`, só o subset latin, `font-display: swap`). Texto e preço: Figtree Variable. Escala fluida `--fs-sm … --fs-hero`, entrelinha 1,06 (título) e 1,6 (texto), medida 62ch.

## Forma
Moldura em arco (topo semicircular) para a foto principal: lembra a boca do forno. Cartões com raio 24px, botão pílula, sem borda grossa. Profundidade por tom, sombra só no hover.

## Sem foto
Placeholder de marca: bloco 4:3 com o símbolo-cookie do logo sobre um tom do doce (caramelo para cookie, brownie para brownie, rosa suave para os demais).

## Movimento
Entrada do hero ao carregar (uma sequência), revelação das fotos por clip-path ao rolar (`animation-timeline: view()`, sem JS e com conteúdo visível por padrão) e hover nos cartões. `prefers-reduced-motion` desliga tudo.
