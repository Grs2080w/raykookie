// Testes da lógica da Prospecção (shared/prospection.ts). Rodar: node --test tests/prospection/
// O app tem o porte em mobile-common/lib/core/prospection.dart com os mesmos casos (mobile-common/test/prospection_test.dart).
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  DEFAULT_GLOBALS, allocate, calcProspect, csvCell, csvFileName, newItem, priceFromMargin, priceFromMarkup, tierPct, toCsv,
} from '../../shared/prospection.ts';

const G = (over = {}) => ({ ...DEFAULT_GLOBALS, minMarginPct: 20, ...over });
const I = (over = {}) => newItem({ id: 'a', name: 'Brigadeiro', qty: 10, cost: 2, price: 5, ...over });
const calc = (items, g = G()) => calcProspect(items, g);
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} ≉ ${b}`);

describe('preço pela margem e pelo markup', () => {
  it('markup sobre o custo: 10 com 100% → 20', () => assert.equal(priceFromMarkup(10, 100), 20));
  it('markup 0% mantém o custo', () => assert.equal(priceFromMarkup(10, 0), 10));
  it('margem sobre a venda: custo 70 e 30% → 100', () => near(priceFromMargin(70, 30), 100));
  it('margem de 100% ou mais não existe (sem divisão por zero)', () => {
    assert.equal(priceFromMargin(10, 100), 0);
    assert.equal(priceFromMargin(10, 150), 0);
  });
  it('negativos viram 0', () => assert.equal(priceFromMarkup(-5, -10), 0));
  it('item em modo markup usa o markup próprio ou o alvo global', () => {
    const own = calc([I({ priceMode: 'markup', markupPct: 50 })]).rows[0];
    assert.equal(own.unitPrice, 3);
    const glob = calc([I({ priceMode: 'markup' })], G({ targetMarkupPct: 100 })).rows[0];
    assert.equal(glob.unitPrice, 4);
  });
});

describe('desconto', () => {
  it('sem desconto: líquido = bruto', () => {
    const r = calc([I()]).rows[0];
    assert.equal(r.gross, 50); assert.equal(r.net, 50); assert.equal(r.discountValue, 0);
  });
  it('percentual no item (10% de 50 = 5)', () => {
    const r = calc([I({ discountMode: 'pct', discount: 10 })]).rows[0];
    assert.equal(r.discountValue, 5); assert.equal(r.net, 45); assert.equal(r.finalUnit, 4.5); near(r.discountPct, 10);
  });
  it('em reais no item (total da linha)', () => {
    const r = calc([I({ discountMode: 'brl', discount: 8 })]).rows[0];
    assert.equal(r.net, 42); assert.equal(r.finalUnit, 4.2);
  });
  it('desconto geral vale para quem não sobrescreve; o item sobrescreve', () => {
    const g = G({ discountMode: 'pct', discount: 20 });
    const { rows } = calc([I({ id: 'a' }), I({ id: 'b', discountMode: 'pct', discount: 0 })], g);
    assert.equal(rows[0].net, 40); assert.equal(rows[1].net, 50);
  });
  it('desconto em R$ maior que o total é limitado ao total e avisa', () => {
    const r = calc([I({ discountMode: 'brl', discount: 999 })]).rows[0];
    assert.equal(r.net, 0); assert.equal(r.discountValue, 50); assert.ok(r.issues.some((s) => /maior que o total/.test(s)));
  });
  it('percentual acima de 100 é limitado a 100', () => {
    const r = calc([I({ discountMode: 'pct', discount: 250 })]).rows[0];
    assert.equal(r.net, 0); assert.ok(r.issues.some((s) => /100%/.test(s)));
  });
  it('desconto negativo é ignorado', () => {
    const r = calc([I({ discountMode: 'pct', discount: -10 })]).rows[0];
    assert.equal(r.net, 50);
  });
});

describe('faixas progressivas por quantidade', () => {
  const tiers = [{ minQty: 10, pct: 5 }, { minQty: 50, pct: 10 }];
  it('escolhe a maior faixa atingida', () => {
    assert.equal(tierPct(tiers, 9), 0);
    assert.equal(tierPct(tiers, 10), 5);
    assert.equal(tierPct(tiers, 49), 5);
    assert.equal(tierPct(tiers, 50), 10);
    assert.equal(tierPct(tiers, 500), 10);
  });
  it('não depende da ordem em que as faixas foram digitadas', () => assert.equal(tierPct([...tiers].reverse(), 60), 10));
  it('faixa com minQty 0 ou inválida é ignorada', () => assert.equal(tierPct([{ minQty: 0, pct: 90 }, { minQty: NaN, pct: 90 }], 5), 0));
  it('aplica no item e substitui o desconto simples', () => {
    const g = G({ tiers });
    const r = calc([I({ qty: 50, useTiers: true, discountMode: 'pct', discount: 1 })], g).rows[0];
    near(r.discountPct, 10); assert.equal(r.net, 225);
  });
  it('abaixo da primeira faixa cai no desconto simples', () => {
    const g = G({ tiers });
    const r = calc([I({ qty: 5, useTiers: true, discountMode: 'pct', discount: 2 })], g).rows[0];
    near(r.discountPct, 2);
  });
  it('sem useTiers, as faixas não valem', () => {
    const r = calc([I({ qty: 50 })], G({ tiers })).rows[0];
    assert.equal(r.discountValue, 0);
  });
});

describe('imposto, lucro, margem e markup', () => {
  it('imposto incide sobre o BRUTO, não sobre o líquido', () => {
    const r = calc([I({ discountMode: 'pct', discount: 20, taxPct: 10 })]).rows[0];
    assert.equal(r.tax, 5); // 10% de 50 (bruto), não de 40
    assert.equal(r.net, 40);
  });
  it('lucro = líquido − imposto − custo − extras − frete', () => {
    const r = calc([I({ taxPct: 10, extra: 3 })], G({ freight: 4 })).rows[0];
    // 50 − 5 − 20 − 3 − 4
    assert.equal(r.profit, 18);
  });
  it('margem sobre a venda e markup sobre o custo (10 un, custo 2, preço 5, sem extras)', () => {
    const r = calc([I()]).rows[0];
    assert.equal(r.profit, 30); near(r.marginPct, 60); near(r.markupPct, 150);
  });
  it('imposto do item sobrescreve o geral', () => {
    const g = G({ taxPct: 10 });
    const { rows } = calc([I({ id: 'a' }), I({ id: 'b', taxPct: 0 })], g);
    assert.equal(rows[0].tax, 5); assert.equal(rows[1].tax, 0);
  });
  it('totais somam as linhas e a margem total é lucro ÷ líquido total', () => {
    const { rows, totals } = calc([I({ id: 'a' }), I({ id: 'b', cost: 4, price: 5 })]);
    assert.equal(totals.gross, 100); assert.equal(totals.net, 100);
    assert.equal(totals.profit, rows[0].profit + rows[1].profit);
    near(totals.marginPct, 40); // (30 + 10) ÷ 100
  });
});

describe('alertas de margem', () => {
  it('ok / baixa / negativa', () => {
    assert.equal(calc([I()]).rows[0].level, 'ok');
    assert.equal(calc([I({ cost: 4.5 })]).rows[0].level, 'low'); // margem 10% < 20%
    assert.equal(calc([I({ cost: 6 })]).rows[0].level, 'negative');
  });
  it('mínimo configurável', () => {
    assert.equal(calc([I()], G({ minMarginPct: 70 })).rows[0].level, 'low');
  });
  it('margem exatamente no mínimo não alerta', () => {
    assert.equal(calc([I({ cost: 4 })], G({ minMarginPct: 20 })).rows[0].level, 'ok');
  });
});

describe('rateio do frete', () => {
  it('proporcional ao bruto e fecha exatamente no total', () => {
    const { rows, totals } = calc([I({ id: 'a', price: 5 }), I({ id: 'b', price: 15 })], G({ freight: 40 }));
    assert.equal(rows[0].freight, 10); assert.equal(rows[1].freight, 30); assert.equal(totals.freight, 40);
  });
  it('centavos que sobram são distribuídos (1,00 entre 3 → 0,34 + 0,33 + 0,33)', () => {
    const out = allocate(1, [1, 1, 1]);
    near(out.reduce((s, x) => s + x, 0), 1);
    assert.deepEqual([...out].sort(), [0.33, 0.33, 0.34]);
  });
  it('soma sempre igual ao total, mesmo com pesos quebrados', () => {
    const out = allocate(100, [33.33, 66.67, 12.5, 7.77]);
    near(out.reduce((s, x) => s + x, 0), 100, 1e-9);
  });
  it('bruto zero em todos: divide por igual', () => {
    assert.deepEqual(allocate(10, [0, 0]), [5, 5]);
  });
  it('frete negativo vira 0; lista vazia não quebra', () => {
    assert.deepEqual(allocate(-10, [1, 1]), [0, 0]);
    assert.deepEqual(allocate(10, []), []);
  });
  it('frete reduz o lucro total', () => {
    const sem = calc([I()]).totals.profit, com = calc([I()], G({ freight: 7 })).totals.profit;
    assert.equal(sem - com, 7);
  });
});

describe('casos extremos', () => {
  it('quantidade 0: nada de divisão por zero', () => {
    const r = calc([I({ qty: 0 })]).rows[0];
    assert.equal(r.finalUnit, 0); assert.equal(r.marginPct, 0);
    assert.ok(r.issues.some((s) => /quantidade/i.test(s)));
  });
  it('preço 0 com custo: prejuízo, margem 0 e sem NaN', () => {
    const r = calc([I({ price: 0 })]).rows[0];
    assert.equal(r.level, 'negative'); assert.equal(r.marginPct, 0);
    for (const v of Object.values(r)) if (typeof v === 'number') assert.ok(Number.isFinite(v));
  });
  it('custo 0: markup 0, sem divisão por zero', () => {
    const r = calc([I({ cost: 0 })]).rows[0];
    assert.equal(r.markupPct, 0); assert.equal(r.profit, 50);
  });
  it('NaN, negativos e vazio viram 0 com aviso', () => {
    const r = calc([I({ qty: NaN, cost: -3, price: -1 })]).rows[0];
    assert.equal(r.gross, 0); assert.ok(r.issues.length >= 2);
  });
  it('sem itens: totais zerados', () => {
    const { rows, totals } = calc([]);
    assert.deepEqual(rows, []); assert.equal(totals.net, 0); assert.equal(totals.level, 'ok');
  });
  it('precisão: 0,1 + 0,2 não vira 0,30000000000000004', () => {
    const { totals } = calc([I({ id: 'a', qty: 1, price: 0.1, cost: 0 }), I({ id: 'b', qty: 1, price: 0.2, cost: 0 })]);
    assert.equal(totals.gross, 0.3);
  });
});

describe('CSV', () => {
  const sample = () => calc([I({ name: 'Brigadeiro', discountMode: 'pct', discount: 10 }), I({ id: 'b', name: 'Bolo; "grande"', qty: 2.5, cost: 10, price: 30 })]);
  it('começa com BOM UTF-8, usa ";" e vírgula decimal', () => {
    const { rows, totals } = sample();
    const csv = toCsv(rows, totals);
    assert.equal(csv.charCodeAt(0), 0xfeff);
    const lines = csv.slice(1).trim().split('\r\n');
    assert.equal(lines.length, 4); // cabeçalho + 2 itens + total
    assert.ok(lines[0].startsWith('Produto;Qtd;'));
    assert.ok(lines[1].startsWith('Brigadeiro;10;2,00;5,00;5,00;10,00;4,50;50,00;45,00'));
    assert.ok(lines[3].startsWith('TOTAL;12,5;'));
    assert.ok(!/\d\.\d/.test(lines[1]), 'sem ponto decimal');
  });
  it('escapa ; e aspas no nome', () => assert.equal(csvCell('Bolo; "grande"'), '"Bolo; ""grande"""'));
  it('neutraliza fórmula do Excel (=, +, -, @)', () => {
    for (const s of ['=1+1', '+cmd', '-2', '@x']) assert.ok(csvCell(s).startsWith("'"));
  });
  it('só exporta as linhas recebidas (as selecionadas)', () => {
    const { rows, totals } = sample();
    const csv = toCsv(rows.slice(0, 1), totals);
    assert.equal(csv.trim().split('\r\n').length, 3);
  });
  it('nome do arquivo leva a data', () => assert.equal(csvFileName('2026-10-19'), 'prospeccao_2026-10-19.csv'));
});
