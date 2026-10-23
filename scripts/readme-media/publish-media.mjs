// Leva os assets gerados para docs/media/: mockups PNG viram WebP (com transparência) e os clipes são copiados.
// Uso: node scripts/readme-media/publish-media.mjs <pasta-dos-mockups-png> <pasta-dos-clipes> [pasta-de-destino]
// Precisa do `cwebp` (libwebp) no PATH.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [mockups, clips] = [path.resolve(process.argv[2]), path.resolve(process.argv[3])];
const dest = path.resolve(process.argv[4] || 'docs/media');
fs.mkdirSync(dest, { recursive: true });

for (const f of fs.readdirSync(mockups).filter((n) => n.endsWith('.png'))) {
  execFileSync('cwebp', ['-quiet', '-q', '88', '-alpha_q', '100', '-m', '6', path.join(mockups, f), '-o', path.join(dest, f.replace(/\.png$/, '.webp'))]);
}
// o MP4 do hero é a versão leve do GIF; os demais clipes entram só como GIF (é o que o GitHub anima em <img>)
for (const f of ['hero-loop.gif', 'hero-loop.mp4', 'web-rolagem.gif', 'web-pedido.gif', 'app-cardapio.gif', 'app-pedido.gif']) {
  if (fs.existsSync(path.join(clips, f))) fs.copyFileSync(path.join(clips, f), path.join(dest, f)); // sem o clipe, só os mockups são atualizados
}
for (const f of fs.readdirSync(dest).sort()) console.log((fs.statSync(path.join(dest, f)).size / 1024).toFixed(0).padStart(6), 'KB', f);
