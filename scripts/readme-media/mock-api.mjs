// API de mentira, só para gerar as capturas do README: dados 100% fictícios, nenhum acesso ao backend real.
// Uso: node scripts/readme-media/mock-api.mjs [porta]   (padrão 10006, a porta que o `next dev` espera em /api)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PORT = Number(process.argv[2] || 10006);
const FOTOS = { 1: 'cookie-destaque.jpg', 2: 'brownie-destaque.jpg', 3: 'bolo-destaque-800.jpg', 4: 'bancada-destaque-800.jpg' };

const item = (id, name, price, category, image, extra = {}) => ({ id, name, price, category, ...(image ? { image: `/api/fotos/${image}` } : {}), ...extra });
const items = [
  item('r1', 'Cookie de Ninho com Nutella', 14, 'Cookies', 1, { weight_g: 110, tags: ['contem_gluten', 'contem_leite', 'contem_ovos'] }),
  item('r2', 'Cookie Red Velvet', 13, 'Cookies', null, { weight_g: 110, tags: ['contem_gluten', 'contem_leite'] }),
  item('r3', 'Cookie de Chocolate Belga', 15, 'Cookies', 1, { weight_g: 110, tags: ['contem_gluten', 'contem_ovos'] }),
  item('r4', 'Brownie Tradicional', 12, 'Brownies', 2, { weight_g: 90, tags: ['contem_gluten', 'contem_ovos', 'contem_castanhas'] }),
  item('r5', 'Brownie de Doce de Leite', 13, 'Brownies', null, { weight_g: 90, tags: ['contem_gluten', 'contem_leite'] }),
  item('r6', 'Bolo de Cenoura com Brigadeiro', 68, 'Bolos', 3, { weight_g: 900, tags: ['contem_gluten', 'contem_leite', 'contem_ovos'] }),
  item('r7', 'Bolo de Chocolate Trufado', 78, 'Bolos', null, { weight_g: 1000, tags: ['contem_gluten', 'contem_leite'] }),
  item('r8', 'Torta de Limão', 54, 'Tortas', 4, { weight_g: 800, tags: ['contem_gluten', 'contem_leite'] }),
  item('r9', 'Brigadeiros Gourmet (caixa com 12)', 42, 'Docinhos', null, { tags: ['contem_leite'] }),
  item('c1', 'Caixa Sextou (4 cookies + 2 brownies)', 78, 'Combos', 1, { combo: { contents: '4 cookies e 2 brownies', full_price: 84, savings: 6, parts: [{ id: 'r1', name: 'Cookie de Ninho com Nutella', qty: 4 }, { id: 'r4', name: 'Brownie Tradicional', qty: 2 }] } }),
];
const groups = ['Combos', 'Cookies', 'Brownies', 'Bolos', 'Tortas', 'Docinhos'].map((n) => ({ id: n.toLowerCase(), name: n, items: items.filter((i) => i.category === n) }));
const menu = { items, groups };

const customer = { id: 1, email: 'maria.exemplo@example.com', name: 'Maria Exemplo', phone: '(11) 90000-0000', cep: '01001-000', street: 'Rua das Flores', number: '100', complement: '', district: 'Centro', city: 'São Paulo' };
const day = (n) => new Date(Date.now() + n * 864e5).toISOString().slice(0, 10);
const base = { pickup_address: 'Rua das Flores, 100 · Centro', street: '', number: '', complement: '', district: '', city: '', payment_method: 'pix', notes: '', paid: true, manual: false, can_cancel: false, payment_state: 'pago', refunded_total: 0, cancelled_by: null, cancel_reason: '', cancel: null, refund: { mode: 'nenhum', max_amount: 0, photos_required: false, deadline: null, why_not: '' }, refunds: [], pix: null, fulfillment: 'retirada', time_window: '14h às 16h' };
const orders = [
  { ...base, id: 3, code: 48213, created_at: new Date().toISOString(), scheduled_for: day(2), total: 78, status: 'producao', items: [{ id: 'c1', name: 'Caixa Sextou (4 cookies + 2 brownies)', qty: 1, unit_price: 78 }] },
  { ...base, id: 2, code: 31877, created_at: new Date(Date.now() - 6 * 864e5).toISOString(), scheduled_for: day(-4), total: 68, status: 'entregue', items: [{ id: 'r6', name: 'Bolo de Cenoura com Brigadeiro', qty: 1, unit_price: 68 }] },
  { ...base, id: 1, code: 20954, created_at: new Date(Date.now() - 20 * 864e5).toISOString(), scheduled_for: day(-18), total: 40, status: 'entregue', items: [{ id: 'r1', name: 'Cookie de Ninho com Nutella', qty: 2, unit_price: 14 }, { id: 'r5', name: 'Brownie de Doce de Leite', qty: 1, unit_price: 12 }] },
];

const routes = {
  '/api/menu': () => menu,
  '/api/pickup': () => ({ label: 'Retirada na confeitaria', address: 'Rua das Flores, 100 · Centro', instructions: 'Toque o interfone e avise o nome do pedido.' }),
  '/api/downloads': () => ({}),
  '/api/cliente/config': () => ({ googleEnabled: true }),
  '/api/payment-config': () => ({ cardEnabled: false, publicKey: null, brands: [], statusScreenBrick: false }),
};
const authed = (req) => /(^|; )mock=1/.test(req.headers.cookie || '') || /^Bearer mock/.test(req.headers.authorization || '');

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const send = (code, body, type = 'application/json') => {
    res.writeHead(code, { 'content-type': type, 'access-control-allow-origin': '*', 'access-control-allow-headers': 'authorization, content-type, x-client', 'access-control-allow-methods': '*' });
    res.end(type === 'application/json' ? JSON.stringify(body) : body);
  };
  if (req.method === 'OPTIONS') return send(204, '');
  const foto = url.pathname.match(/^\/api\/fotos\/(\d+)/);
  if (foto) {
    const f = FOTOS[foto[1]];
    return f ? send(200, fs.readFileSync(path.join(ROOT, 'frontend/public/fotos', f)), 'image/jpeg') : send(404, {});
  }
  const vitrine = url.pathname.match(/^\/fotos\/([\w.-]+\.jpg)$/); // fotos de vitrine do site (public/fotos), com CORS para o app web
  if (vitrine) {
    const file = path.join(ROOT, 'frontend/public/fotos', vitrine[1]);
    return fs.existsSync(file) ? send(200, fs.readFileSync(file), 'image/jpeg') : send(404, {});
  }
  if (routes[url.pathname]) return send(200, routes[url.pathname]());
  if (url.pathname === '/api/cliente/me') return authed(req) ? send(200, customer) : send(401, { error: 'Sem sessão' });
  if (url.pathname === '/api/cliente/pedidos' && req.method === 'GET') return authed(req) ? send(200, orders) : send(401, {});
  if (url.pathname === '/api/cliente/login' || url.pathname === '/api/cliente/cadastro') return send(200, { token: 'mock-token', ok: true });
  send(404, { error: 'mock: rota não existe' });
}).listen(PORT, '127.0.0.1', () => console.log(`mock-api em http://127.0.0.1:${PORT}`));
