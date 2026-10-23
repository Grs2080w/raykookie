#!/usr/bin/env python3
"""Gera a pasta vitrine-publica/: o README da raiz e só o que ele precisa, para virar um repositório público à parte.

O repositório principal é privado, então tudo que o README liga e não vai junto (código, docs técnicos, testes,
scripts) vira texto simples. Só entram as mídias que o README usa (docs/media/) e a LICENSE.
Os docs/API.md, ARQUITETURA.md e SEGURANCA.md ficam de fora de propósito: listam rotas, variáveis de ambiente e
portas da produção.

Uso (da raiz do repositório): python3 scripts/build-vitrine-publica.py
"""
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "vitrine-publica"
LINK = re.compile(r"\[([^\]]+)\]\(([^)\s]+(?:\([^)]*\))?[^)\s]*)\)")  # aceita um par de parênteses no destino: frontend/app/(app)

# ── 1. README: só liga o que existe na pasta
readme = (ROOT / "README.md").read_text(encoding="utf-8")


def unlink(m: re.Match) -> str:
    text, target = m.group(1), m.group(2)
    keep = target.startswith(("http", "#", "docs/media/"))
    return m.group(0) if keep else text  # texto do link fica; o destino não existe aqui


readme = LINK.sub(unlink, readme)

# frases inteiras que só faziam sentido com os arquivos do repositório privado
cuts = [
    (" Para o desenho completo veja `docs/ARQUITETURA.md`, `docs/API.md` e `docs/SEGURANCA.md`.", ""),
    (" O passo a passo para regenerar tudo está em `scripts/readme-media/`.", ""),
    (" O plano e as decisões estão em `docs/superpowers/plans`.", ""),
    ("; detalhes em `docs/SEGURANCA.md`", ""),
    (" em `tests/security`", ""),
]
for old, new in cuts:
    if old not in readme:
        sys.exit(f"trecho não encontrado (o README mudou?): {old!r}")
    readme = readme.replace(old, new)

readme = re.sub(r"\n<sub>Documentação técnica:[^\n]*</sub>\n", "\n", readme)

# aviso de que este é o espelho público
nota = (
    "> Este repositório é a **vitrine pública** do projeto: README, imagens e animações. O código-fonte é privado. "
    "Os nomes de arquivos e pastas citados abaixo existem no repositório de origem.\n\n"
)
readme = readme.replace("---\n\n## Visão geral", nota + "---\n\n## Visão geral", 1)

# ── 2. Pasta de saída
shutil.rmtree(OUT, ignore_errors=True)
(OUT / "docs" / "media").mkdir(parents=True)
(OUT / "README.md").write_text(readme, encoding="utf-8")
shutil.copy(ROOT / "LICENSE", OUT / "LICENSE")

usadas = sorted(set(re.findall(r"docs/media/[A-Za-z0-9._-]+", readme)))
for rel in usadas:
    src = ROOT / rel
    if not src.exists():
        sys.exit(f"mídia citada no README não existe: {rel}")
    shutil.copy(src, OUT / rel)

# ── 3. Conferência: nenhum link local quebrado
quebrados = [
    t for _, t in LINK.findall(readme)
    if not t.startswith(("http", "#")) and not (OUT / t).exists()
]
quebrados += [p for p in re.findall(r'(?:src|href)="([^"]+)"', readme) if not p.startswith("http") and not (OUT / p).exists()]
if quebrados:
    sys.exit(f"links locais quebrados: {quebrados}")
print(f"ok: {OUT.name}/ com {len(usadas)} mídias, README e LICENSE")
