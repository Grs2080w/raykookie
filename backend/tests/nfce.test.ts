import { describe, expect, it } from 'vitest';
import { accessKeyFromText, brNumber, isNfceUrl, matchIngredient, nameTokens, parseNfceHtml, planAdd, planCreate, sizeInName } from '@/lib/nfce';

const KEY = '35260912345678000190650010000123451000123456';
const URL_QR = `https://www.nfce.fazenda.sp.gov.br/qrcode?p=${KEY}|2|1|1|ABCDEF`;

// Página no layout padrão do "Consulta NFC-e" (tabela #tabResult)
const portal = `<html><body>
<div id="u20" class="txtCenter"><div id="u20" class="txtTopo">SUPERMERCADO BOM PRECO LTDA</div><div class="text">CNPJ: 12.345.678/0001-90</div></div>
<table id="tabResult">
 <tr id="Item + 1"><td valign="top"><span class="txtTit">LEITE COND MO&Ccedil;A 395G</span><span class="RCod">&nbsp;(C&oacute;digo: 7891000)</span><br>
   <span class="Rqtd"><strong>Qtde.:</strong>2,0000</span><span class="RUN"><strong>UN: </strong>UN</span><span class="RvlUnit"><strong>Vl. Unit.:</strong>&nbsp;&nbsp;5,4900</span></td>
   <td align="right" valign="top" class="txtTit noWrap">Vl. Total<br><span class="valor">10,98</span></td></tr>
 <tr id="Item + 2"><td valign="top"><span class="txtTit">A&Ccedil;UCAR REFINADO</span><span class="RCod">(C&oacute;digo: 12)</span><br>
   <span class="Rqtd"><strong>Qtde.:</strong>1,5000</span><span class="RUN"><strong>UN: </strong>KG</span><span class="RvlUnit"><strong>Vl. Unit.:</strong> 4,2000</span></td>
   <td align="right"><span class="valor">6,30</span></td></tr>
 <tr id="Item + 3"><td valign="top"><span class="txtTit">LEITE COND MO&Ccedil;A 395G</span><span class="RCod">(C&oacute;digo: 7891000)</span><br>
   <span class="Rqtd"><strong>Qtde.:</strong>1,0000</span><span class="RUN"><strong>UN: </strong>UN</span><span class="RvlUnit"><strong>Vl. Unit.:</strong> 5,4900</span></td>
   <td align="right"><span class="valor">5,49</span></td></tr>
</table>
<div id="totalNota"><label>Valor a pagar R$:</label><span class="totalNumb txtMax">22,77</span></div>
<ul><li><strong>Emiss&atilde;o:</strong> 12/09/2026 10:32:11 - Via Consumidor</li></ul>
</body></html>`;

// Outro layout: sem as classes do portal padrão, só o texto
const plainLayout = `<div><h4>MERCADINHO X</h4><table><tr><td>MANTEIGA COM SAL 200G (Código: 55) Qtde.: 3 UN: UN Vl. Unit.: 9,90 Vl. Total 29,70</td></tr>
<tr><td>CHOCOLATE EM PO 50% 200G Qtde.: 1 UN: UN Vl. Unit.: 12,50 Vl. Total 12,50</td></tr></table><p>Valor total R$ 42,20</p><p>Emissão: 01/10/2026 09:00:00</p></div>`;

describe('parseNfceHtml', () => {
  it('lê o layout padrão do portal: nome, quantidade, unidade, preços, loja, data e total', () => {
    const n = parseNfceHtml(portal, URL_QR)!;
    expect(n.key).toBe(KEY);
    expect(n.store).toBe('SUPERMERCADO BOM PRECO LTDA');
    expect(n.date).toBe('2026-09-12');
    expect(n.total).toBe(22.77);
    // o leite aparece duas vezes: vira uma linha só, 3 unidades
    expect(n.items).toHaveLength(2);
    expect(n.items[0]).toMatchObject({ name: 'LEITE COND MOÇA 395G', code: '7891000', qty: 3, unit: 'UN', unit_price: 5.49, total: 16.47 });
    expect(n.items[1]).toMatchObject({ name: 'AÇUCAR REFINADO', qty: 1.5, unit: 'KG', unit_price: 4.2, total: 6.3 });
  });
  it('usa o plano B quando o layout é outro', () => {
    const n = parseNfceHtml(plainLayout, 'https://x.fazenda.gov.br/n?p=1')!;
    expect(n.items.map((i) => [i.name, i.qty, i.unit_price, i.total])).toEqual([['MANTEIGA COM SAL 200G', 3, 9.9, 29.7], ['CHOCOLATE EM PO 50% 200G', 1, 12.5, 12.5]]);
    expect(n.items[0].code).toBe('55');
    expect(n.total).toBe(42.2);
    expect(n.key.startsWith('h:')).toBe(true); // sem chave no link: usa uma impressão digital do conteúdo
  });
  it('devolve null quando a página não tem itens (captcha, erro, site fora do ar)', () => {
    expect(parseNfceHtml('<html><body>Informe o captcha para continuar</body></html>')).toBeNull();
  });
  it('a mesma nota gera sempre a mesma chave (trava de nota repetida)', () => {
    expect(parseNfceHtml(plainLayout)!.key).toBe(parseNfceHtml(plainLayout)!.key);
  });
});

describe('utilitários', () => {
  it('brNumber', () => {
    expect(brNumber('4,9900')).toBe(4.99);
    expect(brNumber('1.234,56')).toBe(1234.56);
    expect(brNumber('1.000.000')).toBe(1_000_000);
    expect(brNumber('R$ 7,5')).toBe(7.5);
  });
  it('chave de acesso do link do QR code', () => {
    expect(accessKeyFromText(URL_QR)).toBe(KEY);
    expect(accessKeyFromText(`http://x.gov.br/?p=${encodeURIComponent(KEY + '|2')}`)).toBe(KEY);
    expect(accessKeyFromText('https://x.gov.br/?p=123')).toBeNull();
  });
  it('só aceita links de portais .gov.br', () => {
    expect(isNfceUrl(URL_QR)).toBe(true);
    expect(isNfceUrl('http://www.fazenda.pr.gov.br/nfce/qrcode?p=1')).toBe(true);
    expect(isNfceUrl('https://gov.br.evil.com/x')).toBe(false);
    expect(isNfceUrl('https://evilgov.br/x')).toBe(false);
    expect(isNfceUrl('file:///etc/passwd')).toBe(false);
    expect(isNfceUrl('nao e url')).toBe(false);
  });
  it('tamanho escrito no nome', () => {
    expect(sizeInName('LEITE COND 395G')).toEqual({ unit: 'g', size: 395 });
    expect(sizeInName('ACUCAR 1,5 KG')).toEqual({ unit: 'g', size: 1500 });
    expect(sizeInName('LEITE 1L')).toEqual({ unit: 'ml', size: 1000 });
    expect(sizeInName('SUCO 200ML')).toEqual({ unit: 'ml', size: 200 });
    expect(sizeInName('OVOS BRANCOS')).toBeNull();
  });
  it('palavras do nome, expandindo abreviações e ignorando medidas', () => {
    expect(nameTokens('LEITE COND MOÇA 395G')).toEqual(['leite', 'condensado', 'moca']);
  });
});

const ings = [
  { id: 1, name: 'Leite condensado', unit: 'g', package_size: 395, package_cost: 5 },
  { id: 2, name: 'Açúcar', unit: 'g', package_size: 1000, package_cost: 4 },
  { id: 3, name: 'Manteiga', unit: 'g', package_size: 200, package_cost: 9 },
  { id: 4, name: 'Leite', unit: 'ml', package_size: 1000, package_cost: 5 },
];

describe('matchIngredient', () => {
  it('casa com abreviações da nota', () => {
    expect(matchIngredient('LEITE COND MOÇA 395G', ings)).toMatchObject({ ingredient_id: 1, exact: true });
    expect(matchIngredient('ACUCAR 1KG', ings)).toMatchObject({ ingredient_id: 2, exact: true }); // item é só "açúcar" + medida
    expect(matchIngredient('MANTEIGA 200G', ings)).toMatchObject({ ingredient_id: 3, exact: true });
    expect(matchIngredient('MANTEIGA COM SAL 200G', ings)).toMatchObject({ ingredient_id: 3, exact: false }); // "com sal" é detalhe: pergunta uma vez e lembra
  });
  it('prefere o ingrediente mais específico', () => {
    expect(matchIngredient('LEITE CONDENSADO PIRACANJUBA', ings)?.ingredient_id).toBe(1);
  });
  it('ingrediente de uma palavra só contra item com mais detalhes (marca, variação) vira sugestão', () => {
    expect(matchIngredient('ACUCAR REFINADO UNIAO 1KG', ings)).toMatchObject({ ingredient_id: 2, exact: false });
    expect(matchIngredient('ACUCAR REFINADO', ings)).toMatchObject({ ingredient_id: 2, exact: false });
  });
  it('produto diferente que só divide uma palavra não é confundido', () => {
    // "Leite" não recebe "creme de leite" sozinho, e "Leite condensado" nem aparece como sugestão para ele
    expect(matchIngredient('CREME DE LEITE 200G', [ings[3]])).toMatchObject({ ingredient_id: 4, exact: false });
    expect(matchIngredient('CREME DE LEITE 200G', [ings[0]])).toBeNull();
    expect(matchIngredient('LEITE CONDENSADO', [ings[3]])).toMatchObject({ exact: false });
  });
  it('nome genérico demais vira sugestão, não soma sozinho', () => {
    const r = matchIngredient('LEITE EM PO INTEGRAL ITAMBE 400G', [ings[3]]);
    expect(r).toMatchObject({ ingredient_id: 4, exact: false });
  });
  it('item sem relação com o estoque não casa', () => {
    expect(matchIngredient('DETERGENTE YPE 500ML', ings)).toBeNull();
    expect(matchIngredient('PAPEL TOALHA', [])).toBeNull();
  });
});

describe('planAdd / planCreate', () => {
  const item = (name: string, qty: number, unit: string, unit_price: number) => ({ name, code: '', qty, unit, unit_price, total: qty * unit_price });
  it('embalagem igual à do cadastro: soma a quantidade e atualiza o preço', () => {
    expect(planAdd(item('LEITE COND 395G', 3, 'UN', 5.49), ings[0])).toMatchObject({ add_qty: 3, package_cost: 5.49 });
  });
  it('embalagem de tamanho diferente: converte para embalagens do cadastro', () => {
    // 2 × 500 g = 1000 g = 1 embalagem de 1 kg; R$ 3 por 500 g = R$ 6 por kg
    expect(planAdd(item('ACUCAR 500G', 2, 'UN', 3), ings[1])).toMatchObject({ add_qty: 1, package_cost: 6 });
  });
  it('vendido por kg: converte para embalagens', () => {
    // 1,5 kg a R$ 4,20/kg; embalagem do cadastro = 1000 g
    expect(planAdd(item('ACUCAR REFINADO', 1.5, 'KG', 4.2), ings[1])).toMatchObject({ add_qty: 1.5, package_cost: 4.2 });
    // embalagem do cadastro de 500 g: 3 embalagens, R$ 2,10 cada
    expect(planAdd(item('ACUCAR REFINADO', 1.5, 'KG', 4.2), { unit: 'g', package_size: 500, package_cost: 2 })).toMatchObject({ add_qty: 3, package_cost: 2.1 });
  });
  it('planCreate deduz unidade e tamanho', () => {
    expect(planCreate(item('CREME DE LEITE 200G', 2, 'UN', 3.29))).toEqual({ name: 'Creme de leite 200g', unit: 'g', package_size: 200, package_cost: 3.29, stock_qty: 2 });
    expect(planCreate(item('FARINHA DE TRIGO', 2, 'KG', 6))).toMatchObject({ unit: 'g', package_size: 1000, package_cost: 6, stock_qty: 2 });
    expect(planCreate(item('OVOS BRANCOS', 1, 'UN', 12))).toMatchObject({ unit: 'un', package_size: 1, package_cost: 12, stock_qty: 1 });
  });
});
