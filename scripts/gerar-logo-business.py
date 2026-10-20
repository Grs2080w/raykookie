#!/usr/bin/env python3
"""Gera as logos "raykookie business": o wordmark oficial + "business" em dourado, tudo em contornos (sem depender de fonte).

Uso (de dentro de outro diretório que não seja o de saída; Python em modo isolado):
  python3 -I scripts/gerar-logo-business.py <fraunces-italic.woff2> <raiz-do-repo>

O "business" é Fraunces itálica (wght 500, opsz 72), a mesma família dos títulos do painel. As cores do dourado
vivem aqui e nos tokens do tema (frontend/app/globals.css e mobile-common/lib/ui/theme.dart): mudou lá, mude aqui.
Os SVGs oficiais (com metadados c2pa) não são alterados: as versões business são arquivos novos.
Também grava a fonte estática do app (mobile-server/assets/fonts/FrauncesItalic.ttf).
"""
import re
import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

TEXTO = "business"
# Dourado por fundo: claro→profundo. No fundo claro o dourado precisa ser mais fechado para manter o contraste.
GRADIENTES = {
    "fundo-escuro": ("#F7E3A3", "#D9A93F", "#A87A22"),
    "fundo-claro": ("#C9962B", "#A67614", "#7A520C"),
    "sobre-rosa": ("#FFF6D6", "#FFE08F", "#F5C451"),  # dourado claro sobre o rosa da marca (só existe a versão compacta)
}
# Linha de base do "kookie" em cada versão (o "y" desce mais, mas fica do outro lado do "business")
LINHA_BASE = {"principal": 0.0, "compacta": 92.0}
ALTURA_ASCENDENTE = 36.0  # altura do "b" na unidade do wordmark
FOLGA = 9.0  # espaço entre o wordmark e o "business"


def contornos(fonte: TTFont) -> tuple[str, float, float]:
    """Caminho SVG do texto (origem na linha de base, y para baixo), largura e altura do "b"."""
    gs = fonte.getGlyphSet()
    cmap = fonte.getBestCmap()
    # altura do "b": maior y do glifo
    from fontTools.pens.boundsPen import BoundsPen

    bp = BoundsPen(gs)
    gs[cmap[ord("b")]].draw(bp)
    asc = bp.bounds[3]
    esc = ALTURA_ASCENDENTE / asc
    pen = SVGPathPen(gs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    x = 0.0
    for ch in TEXTO:
        nome = cmap[ord(ch)]
        tp = TransformPen(pen, (esc, 0, 0, -esc, x * esc, 0))
        gs[nome].draw(tp)
        x += fonte["hmtx"][nome][0] + 20  # 20/2000 de espaçamento entre letras: legível em tamanho pequeno
    return pen.getCommands(), (x - 20) * esc, ALTURA_ASCENDENTE


def compor(corpo: str, viewbox: tuple[float, float, float, float], linha_base: float, d: str, largura: float, gradiente: tuple[str, str, str], ident: str) -> str:
    x0, y0, w, h = viewbox
    base = linha_base + FOLGA + ALTURA_ASCENDENTE
    novo_h = max(y0 + h, base + 3) - y0
    # alinhado à direita, sob o "kookie"; o respiro direito do wordmark (≈0,6) é mantido
    tx = x0 + w - largura - 3
    c1, c2, c3 = gradiente
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0:.2f} {y0:.2f} {w:.2f} {novo_h:.2f}" role="img" aria-label="raykookie business">'
        f'<defs><linearGradient id="{ident}" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{c1}"/><stop offset="0.5" stop-color="{c2}"/><stop offset="1" stop-color="{c3}"/></linearGradient></defs>'
        f"{corpo}"
        f'<path transform="translate({tx:.2f} {base:.2f})" fill="url(#{ident})" d="{d}"/></svg>\n'
    )


def main() -> None:
    woff, raiz = Path(sys.argv[1]), Path(sys.argv[2])
    fonte = instancer.instantiateVariableFont(TTFont(woff), {"wght": 500, "opsz": 72, "SOFT": 0, "WONK": 0})
    d, largura, _ = contornos(fonte)

    # fonte estática para o app mobile (Flutter não precisa dos eixos)
    fonte.flavor = None
    ttf = raiz / "mobile-server/assets/fonts/FrauncesItalic.ttf"
    fonte.save(ttf)

    origem = raiz / "frontend/public/brand"
    destinos = [origem, raiz / "mobile-server/assets/brand"]
    for variante in ("principal", "compacta"):
        for fundo, grad in GRADIENTES.items():
            if not (origem / f"raykookie-{variante}-{fundo}.svg").exists():
                continue
            svg = (origem / f"raykookie-{variante}-{fundo}.svg").read_text()
            svg = re.sub(r"<metadata>.*?</metadata>", "", svg, flags=re.S)
            m = re.search(r'viewBox="([^"]+)"', svg)
            vb = tuple(float(v) for v in m.group(1).split())
            corpo = re.sub(r"^<svg[^>]*>|</svg>\s*$", "", svg.strip())
            out = compor(corpo, vb, LINHA_BASE[variante], d, largura, grad, f"ouro-{variante}-{fundo}")
            for dest in destinos:
                (dest / f"raykookie-business-{variante}-{fundo}.svg").write_text(out)
    print("ok", ttf.stat().st_size, "bytes de fonte")


if __name__ == "__main__":
    main()
