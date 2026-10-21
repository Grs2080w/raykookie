// Monta um clipe (GIF e MP4) a partir dos quadros do screencast (capture-hero.mjs, capture-motion.mjs).
// Uso: node scripts/readme-media/build-clip.mjs <pasta-dos-quadros> <pasta-de-saída> [largura] [fps] [pausa-no-último-quadro-em-s] [nome]
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [dir, out] = [path.resolve(process.argv[2]), path.resolve(process.argv[3])];
const width = Number(process.argv[4] || 880), fps = Number(process.argv[5] || 24), hold = Number(process.argv[6] || 1.0), name = process.argv[7] || 'hero-loop';
const { frames } = JSON.parse(fs.readFileSync(path.join(dir, 'frames.json'), 'utf8'));
fs.mkdirSync(out, { recursive: true });

// concat do ffmpeg: cada quadro dura até o próximo; o último (logo estático) fica na tela por `hold` s antes de repetir
const lines = frames.map((f, i) => `file '${f.file}'\nduration ${(i < frames.length - 1 ? frames[i + 1].ts - f.ts : hold).toFixed(4)}`);
lines.push(`file '${frames.at(-1).file}'`);
const list = path.join(dir, 'concat.txt');
fs.writeFileSync(list, lines.join('\n'));

const run = (args) => execFileSync('ffmpeg', ['-loglevel', 'error', '-y', ...args], { stdio: 'inherit' });
const scale = `fps=${fps},scale=${width}:-2:flags=lanczos`;
const src = ['-f', 'concat', '-safe', '0', '-i', list];

run([...src, '-vf', `${scale},split[a][b];[a]palettegen=max_colors=${process.env.GIF_COLORS || 96}:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle`, '-loop', '0', path.join(out, `${name}.gif`)]);
run([...src, '-vf', `${scale},format=yuv420p`, '-c:v', 'libx264', '-crf', '24', '-movflags', '+faststart', '-an', path.join(out, `${name}.mp4`)]);
for (const f of ['gif', 'mp4']) console.log(f, (fs.statSync(path.join(out, `${name}.${f}`)).size / 1024).toFixed(0), 'KB');
