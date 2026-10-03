import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

String _perLabel(double price, double size, String unit) {
  if (size <= 0) return '';
  final p = price / size;
  String f(double v) => v.toStringAsFixed(2).replaceAll('.', ',');
  return unit == 'un' ? 'R\$ ${f(p)}/un' : 'R\$ ${f(p * 1000)}/${unit == 'g' ? 'kg' : 'L'}';
}

const _source = {'scraper': 'site', 'manual': 'cotação', 'nota': 'nota fiscal', 'edicao': 'edição'};

String _day(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  return d == null ? '' : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
}

/// Preços de um ingrediente: cotações por fornecedor (da mais barata), pesquisa nos sites e histórico.
class PricesScreen extends StatefulWidget {
  const PricesScreen({super.key, required this.ingredient});
  final Ingredient ingredient;
  @override
  State<PricesScreen> createState() => _PricesScreenState();
}

class _PricesScreenState extends State<PricesScreen> {
  int _v = 0;
  late final _term = TextEditingController(text: widget.ingredient.name);
  final _price = TextEditingController(), _size = TextEditingController();
  Map<String, dynamic>? _found;
  final _sizes = <String, TextEditingController>{};
  String _busy = '';
  int? _supplier;

  @override
  void dispose() {
    _term.dispose();
    _price.dispose();
    _size.dispose();
    for (final c in _sizes.values) {
      c.dispose();
    }
    super.dispose();
  }

  ApiClient get _api => context.read<Session>().ownerApi;

  Future<void> _run(String key, Future<void> Function() fn) async {
    setState(() => _busy = key);
    try {
      await fn();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = '');
    }
  }

  @override
  Widget build(BuildContext context) {
    final ing = widget.ingredient;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: Text('Preços · ${ing.name}')),
      body: Loader<Map<String, dynamic>>(
        key: ValueKey(_v),
        load: () async {
          final d = Map<String, dynamic>.from(await _api.get('/api/ingredients/${ing.id}/prices') as Map);
          d['suppliers'] = await _api.get('/api/suppliers');
          return d;
        },
        builder: (context, d, reload) {
          final i = Map<String, dynamic>.from(d['ingredient'] as Map);
          final quotes = [for (final q in d['quotes'] as List) Map<String, dynamic>.from(q as Map)];
          final sups = [for (final s in d['suppliers'] as List) Map<String, dynamic>.from(s as Map)];
          final unit = '${i['unit']}';
          final cur = (i['package_cost'] as num).toDouble(), curSize = (i['package_size'] as num).toDouble();
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const SizedBox(height: 8),
            Text('Hoje no estoque: ${money(cur)} por ${number(curSize)} $unit · ${_perLabel(cur, curSize, unit)}'),
            const SizedBox(height: 14),
            const Text('Cotações por fornecedor', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            if (quotes.isEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('Nenhuma cotação ainda. Pesquise nos sites ou cadastre uma abaixo.', style: TextStyle(color: c.muted))),
            for (final (n, q) in quotes.indexed)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: AppCard(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Wrap(spacing: 8, children: [
                      Text('${q['supplier']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                      if (n == 0) Text('Mais barato', style: TextStyle(color: c.ok, fontWeight: FontWeight.w800)),
                      Text('${_source[q['source']] ?? q['source']} · ${_day('${q['at']}')}', style: TextStyle(color: c.muted)),
                    ]),
                    Text('${money((q['price'] as num).toDouble())} por ${number((q['pack_size'] as num).toDouble())} ${q['pack_unit']} · ${_perLabel((q['price'] as num).toDouble(), (q['pack_size'] as num).toDouble(), '${q['pack_unit']}')}', style: const TextStyle(fontWeight: FontWeight.w700)),
                    if (q['product'] != null) Text('${q['product']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: c.muted, fontSize: 13)),
                    const SizedBox(height: 6),
                    BusyButton(
                      label: 'Usar este preço',
                      outlined: true,
                      expand: false,
                      busy: _busy == 'use${q['id']}',
                      onPressed: () => _run('use${q['id']}', () async {
                        final r = await _api.post('/api/ingredients/${ing.id}/use-price', {'quote_id': q['id']}) as Map;
                        Toast.ok((r['alerts'] as num? ?? 0) > 0 ? 'Preço atualizado. Veja os alertas de margem no início.' : 'Preço do ingrediente atualizado');
                        setState(() => _v++);
                      }),
                    ),
                  ]),
                ),
              ),
            const SizedBox(height: 18),
            const Text('Pesquisar nos sites dos fornecedores', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Expanded(child: AppTextField(label: 'O que procurar', controller: _term, maxLength: 80)),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.only(bottom: 14),
                child: BusyButton(
                  label: 'Buscar',
                  expand: false,
                  busy: _busy == 'search',
                  onPressed: () => _run('search', () async {
                    final r = Map<String, dynamic>.from(await _api.post('/api/prices/search', {'ingredient_id': ing.id, 'term': _term.text}) as Map);
                    setState(() => _found = r);
                  }),
                ),
              ),
            ]),
            if (_found != null) ...[
              for (final e in _found!['errors'] as List) Text('${(e as Map)['supplier']} precisa de revisão: ${e['error']}', style: TextStyle(color: c.bad)),
              if ((_found!['results'] as List).isEmpty && (_found!['errors'] as List).isEmpty)
                Text('Nenhum fornecedor com busca cadastrado. Cadastre um site em Fornecedores.', style: TextStyle(color: c.muted)),
              for (final r in _found!['results'] as List) ...[
                Padding(padding: const EdgeInsets.only(top: 8), child: Text('${(r as Map)['supplier']}', style: const TextStyle(fontWeight: FontWeight.w800))),
                if ((r['products'] as List).isEmpty) Text('Nada encontrado para “${_found!['term']}”.', style: TextStyle(color: c.muted)),
                for (final p in r['products'] as List)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: AppCard(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${(p as Map)['name']}'),
                        Text(
                          '${money((p['price'] as num).toDouble())}${p['size'] != null && p['unit'] != null ? ' · ${number((p['size'] as num).toDouble())} ${p['unit']} · ${_perLabel((p['price'] as num).toDouble(), (p['size'] as num).toDouble(), '${p['unit']}')}' : ''}',
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        if (p['size'] == null || p['unit'] != unit)
                          AppTextField(
                            label: 'Tamanho da embalagem em $unit',
                            controller: _sizes.putIfAbsent('${p['url']}', () => TextEditingController()),
                            keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            formatters: [NumberFormatter()],
                          ),
                        BusyButton(
                          label: 'Ligar ao ingrediente',
                          outlined: true,
                          expand: false,
                          busy: _busy == 'link${p['url']}',
                          onPressed: () => _run('link${p['url']}', () async {
                            final size = p['size'] != null && p['unit'] == unit ? (p['size'] as num).toDouble() : parseNum(_sizes['${p['url']}']?.text ?? '');
                            await _api.post('/api/prices/link', {'ingredient_id': ing.id, 'supplier_id': r['supplier_id'], 'name': p['name'], 'url': p['url'], 'price': p['price'], 'size': size, 'unit': unit});
                            Toast.ok('Produto ligado: o preço é conferido todo dia');
                            setState(() => _v++);
                          }),
                        ),
                      ]),
                    ),
                  ),
              ],
            ],
            const SizedBox(height: 18),
            const Text('Cadastrar cotação à mão', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            if (sups.isEmpty)
              Text('Cadastre um fornecedor em Fornecedores para guardar cotações.', style: TextStyle(color: c.muted))
            else ...[
              DropdownButtonFormField<int>(
                initialValue: _supplier,
                hint: const Text('Fornecedor'),
                isExpanded: true,
                items: [for (final s in sups) DropdownMenuItem(value: s['id'] as int, child: Text('${s['name']}'))],
                onChanged: (v) => setState(() => _supplier = v),
              ),
              AppTextField(label: 'Preço (R\$)', controller: _price, keyboardType: const TextInputType.numberWithOptions(decimal: true), formatters: [NumberFormatter()]),
              AppTextField(label: 'Tamanho ($unit)', controller: _size, keyboardType: const TextInputType.numberWithOptions(decimal: true), formatters: [NumberFormatter()]),
              BusyButton(
                label: 'Guardar cotação',
                busy: _busy == 'quote',
                onPressed: _supplier == null
                    ? null
                    : () => _run('quote', () async {
                          await _api.post('/api/prices/quote', {'ingredient_id': ing.id, 'supplier_id': _supplier, 'price': parseNum(_price.text), 'pack_size': parseNum(_size.text)});
                          Toast.ok('Cotação guardada');
                          _price.clear();
                          _size.clear();
                          setState(() => _v++);
                        }),
              ),
            ],
            const SizedBox(height: 18),
            const Text('Histórico', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            for (final h in d['history'] as List)
              Text('${_day('${(h as Map)['at']}')} · ${money((h['price'] as num).toDouble())} por ${number((h['pack_size'] as num).toDouble())} $unit · ${h['supplier'] ?? _source[h['source']] ?? h['source']}', style: TextStyle(color: c.muted, fontSize: 13)),
          ]);
        },
      ),
    );
  }
}
