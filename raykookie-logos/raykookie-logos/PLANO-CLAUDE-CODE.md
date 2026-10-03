# raykookie: plano de implementação das logos

Instruções para o Claude Code (ou qualquer dev) aplicar a identidade da raykookie num site ou app. Use os arquivos prontos deste pacote. Não redesenhe as logos e não troque os SVGs por texto com fonte.

## 1. Estrutura do pacote

```
raykookie-logos/
├─ svg/        vetores finais (use estes no código)
├─ png/        versões em bitmap com fundo transparente (2000px de largura; símbolo com 1024px)
├─ favicon/    favicon.svg, favicon.ico (16/32/48), PNGs 16/32/48/192/512, apple-touch-icon.png
├─ redes-sociais/
│  ├─ perfil/     fotos de perfil quadradas (1080, 800, 400px)
│  └─ opengraph/  imagem de compartilhamento 1200×630
├─ site.webmanifest
└─ PLANO-CLAUDE-CODE.md
```

Todos os SVGs têm o texto convertido em curvas (fonte Archivo Black), então não dependem de nenhuma fonte instalada. O corte da mordida é um path único, sem máscaras.

## 2. Cores

| Papel | Hex |
|---|---|
| Rosa (marca) | `#E94B8A` |
| Escuro (tinta) | `#1E1A1D` |
| Rosa claro (fundo) | `#FBE9F0` |

Crie estas variáveis CSS e use sempre elas:

```css
:root {
  --rk-rosa: #E94B8A;
  --rk-escuro: #1E1A1D;
  --rk-rosa-claro: #FBE9F0;
}
```

## 3. As logos e a função de cada uma

### A. Logo principal (horizontal): para espaços maiores
“ray” em rosa + “k●●kie”, com os dois “o” trocados por cookies rosa (o segundo está mordido).

Use em: cabeçalho do site no desktop, hero, rodapé, cartão de visita, banners, fachada, cardápio, papel timbrado.

| Arquivo | Quando usar |
|---|---|
| `svg/raykookie-principal-fundo-claro.svg` | Padrão. Sobre fundo branco ou `--rk-rosa-claro`. |
| `svg/raykookie-principal-fundo-escuro.svg` | Sobre fundo `--rk-escuro`. |
| `svg/raykookie-principal-mono-escuro.svg` | Impressão numa cor só, carimbo, gravação. |
| `svg/raykookie-principal-mono-claro.svg` | Uma cor só, clara, sobre foto escura ou fundo rosa. |

Largura mínima: 120px na tela, 30mm impresso. Abaixo disso, use a compacta.

### B. Logo compacta (empilhada): para espaços menores
“ray” em cima e “k●●kie” embaixo, sem linha e sem tagline.

Use em: cabeçalho no celular, foto de perfil nas redes, adesivo de embalagem, etiqueta, tag de sacola, marca d’água em fotos.

| Arquivo | Quando usar |
|---|---|
| `svg/raykookie-compacta-fundo-claro.svg` | Padrão. Sobre fundo claro. |
| `svg/raykookie-compacta-fundo-escuro.svg` | Sobre fundo escuro. |
| `svg/raykookie-compacta-sobre-rosa.svg` | Sobre fundo `--rk-rosa` (adesivo rosa). |
| `svg/raykookie-compacta-mono-escuro.svg` / `-mono-claro.svg` | Uma cor só. |

Largura mínima: 56px na tela, 15mm impresso.

### C. Símbolo / favicon: cookie mordido
Cookie rosa com mordida, sem gotas. É o mesmo desenho do segundo “o”.

Use em: favicon, ícone de app, loading/spinner, marcadores, selos pequenos, elemento gráfico solto.

| Arquivo | Quando usar |
|---|---|
| `svg/raykookie-simbolo-cookie.svg` | Elemento gráfico em qualquer tamanho. |
| `favicon/favicon.svg` | Favicon em navegadores modernos. |
| `favicon/favicon.ico` | Fallback para navegadores antigos (16/32/48). |
| `favicon/apple-touch-icon.png` | Ícone de atalho no iOS (180×180, fundo rosa claro). |
| `favicon/icon-192.png`, `icon-512.png` | PWA / Android (manifest). |

### D. Fotos de perfil (redes sociais)
Quadradas, com fundo cheio. A logo fica dentro da área segura do recorte circular.

| Arquivo | Uso |
|---|---|
| `perfil-fundo-rosa-claro-*.png` | Padrão para Instagram, Facebook e TikTok. |
| `perfil-fundo-escuro-*.png` | Alternativa escura, que destaca bem no feed. |
| `perfil-fundo-rosa-*.png` | Versão chamativa, para campanhas ou datas especiais. |
| `perfil-simbolo-*.png` | Só o cookie. Para WhatsApp Business ou espaços muito pequenos. |

Tamanhos: **1080** (Instagram, TikTok, upload master), **800** (Facebook, LinkedIn, YouTube), **400** (X/Twitter, Google Business, Pinterest). Na dúvida, envie o de 1080, porque todas as redes reduzem automaticamente.

### E. Open Graph (prévia de link)
Imagem que aparece quando o link do site é compartilhado no WhatsApp, Facebook, LinkedIn, X etc. Tem 1200×630px e traz a logo principal com o lema “Confeitaria feita à mão.”

- `redes-sociais/opengraph/og-image-fundo-claro-1200x630.png`: padrão.
- `redes-sociais/opengraph/og-image-fundo-escuro-1200x630.png`: alternativa.

Copie a imagem para `public/og-image.png` e adicione ao `<head>` (troque o domínio):

```html
<meta property="og:title" content="raykookie · Confeitaria feita à mão">
<meta property="og:description" content="Bolos, cookies e doces feitos à mão.">
<meta property="og:image" content="https://SEU-DOMINIO.com/og-image.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:type" content="website">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:image" content="https://SEU-DOMINIO.com/og-image.png">
```

O `og:image` precisa ser uma URL absoluta (com https).

## 4. Regras de uso

- **Área de proteção:** deixe em volta da logo um espaço livre igual à altura de um cookie.
- Não mude as cores, não aplique contorno, sombra nem degradê, e não distorça as proporções.
- Não reescreva “raykookie” com fonte; use sempre o SVG.
- Não use a principal sobre fundo rosa `#E94B8A` (o “ray” some). Nesse caso, use `compacta-sobre-rosa` ou uma versão mono.
- Escolha pelo espaço: com largura de 200px ou mais, use a principal; com menos, use a compacta; em áreas quadradas bem pequenas (abaixo de 48px), use só o símbolo.

## 5. Implementação passo a passo

1. Copie `svg/` para `public/brand/` (ou `assets/brand/`).
2. Copie o conteúdo de `favicon/` e o `site.webmanifest` para a raiz de `public/`.
3. Adicione no `<head>`:

```html
<link rel="icon" href="/favicon.ico" sizes="48x48">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" content="#E94B8A">
```

4. Crie um componente `Logo` que escolha a versão pelas props:

```jsx
// variant: "principal" | "compacta" | "simbolo"
// tone: "fundo-claro" | "fundo-escuro" | "mono-escuro" | "mono-claro" | "sobre-rosa"
export function Logo({ variant = "principal", tone = "fundo-claro", className, ...rest }) {
  const src = variant === "simbolo"
    ? "/brand/raykookie-simbolo-cookie.svg"
    : `/brand/raykookie-${variant}-${tone}.svg`;
  return <img src={src} alt="raykookie" className={className} {...rest} />;
}
```

5. No cabeçalho, alterne as versões conforme a largura da tela:

```jsx
<a href="/" aria-label="raykookie — início">
  <Logo variant="principal" className="hidden md:block h-10 w-auto" />
  <Logo variant="compacta"  className="md:hidden h-12 w-auto" />
</a>
```

6. Defina a altura (`h-*`) e deixe `w-auto`, sem fixar largura e altura ao mesmo tempo.
7. Se precisar mudar a cor via CSS (hover ou tema), coloque o SVG inline em vez de `<img>` e troque os `fill` pelas variáveis `--rk-*`.

## 6. Checklist

- [ ] Favicon aparece na aba (SVG e ICO).
- [ ] O ícone aparece ao adicionar à tela inicial no iOS e no Android.
- [ ] O cabeçalho troca de principal para compacta no celular.
- [ ] Nenhuma logo está esticada ou abaixo do tamanho mínimo.
- [ ] Em fundo escuro, está usando a versão `fundo-escuro`.
- [ ] A prévia do link aparece com a imagem OG (teste em opengraph.xyz ou colando o link no WhatsApp).
