import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

const _periods = [(7, '7 dias'), (30, '30 dias'), (90, '90 dias')];
const _platforms = [('', 'Tudo'), ('site', 'Site'), ('android', 'App Android'), ('ios', 'App iPhone')];
const _sorts = [('views', 'Vistas'), ('clicks', 'Cliques'), ('adds', 'Adicionados'), ('orders', 'Pedidos'), ('revenue', 'Receita')];
const _referrers = {
  'direct': 'Direto',
  'instagram': 'Instagram',
  'whatsapp': 'WhatsApp',
  'search': 'Busca (Google e outros)',
  'other': 'Outros sites',
};
const _platformNames = {'site': 'Site', 'android': 'App Android', 'ios': 'App iPhone'};

String _day(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Engajamento dos clientes: o que mais chama atenção, páginas vistas e até onde chegam no pedido. A medição é
/// anônima; esta tela só mostra o que o servidor calcula (`/api/engagement/*`).
class EngagementScreen extends StatefulWidget {
  const EngagementScreen({super.key});
  @override
  State<EngagementScreen> createState() => _EngagementScreenState();
}

class _EngagementScreenState extends State<EngagementScreen> {
  int _days = 30;
  String _platform = '';
  String _sort = 'clicks';

  String _query() {
    final to = DateTime.now();
    final from = to.subtract(Duration(days: _days - 1));
    return '?from=${_day(from)}&to=${_day(to)}${_platform.isEmpty ? '' : '&platform=$_platform'}';
  }

  Future<Map<String, dynamic>> _load() async {
    final api = context.read<Session>().ownerApi;
    final q = _query();
    Future<Map<String, dynamic>> get(String path) async => Map<String, dynamic>.from(await api.get('/api/engagement/$path') as Map);
    final r = await Future.wait([get('overview$q'), get('funnel$q'), get('recipes$q&sort=$_sort'), get('pages$q'), get('sources$q')]);
    return {'overview': r[0], 'funnel': r[1], 'recipes': r[2], 'pages': r[3], 'sources': r[4]};
  }

  Widget _chips<T>(BuildContext context, List<(T, String)> options, T selected, void Function(T) onTap) {
    final c = context.c;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: [
        for (final o in options)
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(o.$2),
              selected: selected == o.$1,
              onSelected: (_) => setState(() => onTap(o.$1)),
              showCheckmark: false,
              backgroundColor: c.surface,
              selectedColor: c.ink,
              side: BorderSide(color: selected == o.$1 ? c.ink : c.line),
              labelStyle: TextStyle(color: selected == o.$1 ? c.bg : c.ink, fontWeight: FontWeight.w700, fontSize: 14),
              materialTapTargetSize: MaterialTapTargetSize.padded,
            ),
          ),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Engajamento')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Column(children: [
              _chips<int>(context, [for (final p in _periods) (p.$1, 'Últimos ${p.$2}')], _days, (v) => _days = v),
              const SizedBox(height: 4),
              _chips<String>(context, _platforms, _platform, (v) => _platform = v),
            ]),
          ),
          Expanded(
            child: Loader<Map<String, dynamic>>(
              key: ValueKey('$_days|$_platform|$_sort'),
              load: _load,
              builder: (context, d, _) => _Body(d, sort: _sort, onSort: (v) => setState(() => _sort = v), chips: _chips),
            ),
          ),
        ],
      ),
    );
  }
}

class _Body extends StatelessWidget {
  const _Body(this.d, {required this.sort, required this.onSort, required this.chips});
  final Map<String, dynamic> d;
  final String sort;
  final void Function(String) onSort;
  final Widget Function<T>(BuildContext, List<(T, String)>, T, void Function(T)) chips;

  static num _n(Object? v) => v is num ? v : 0;
  static Map<String, dynamic> _m(Object? v) => Map<String, dynamic>.from(v as Map);
  static List<Map<String, dynamic>> _l(Object? v) => [for (final e in (v as List? ?? const [])) _m(e)];

  Widget _title(BuildContext context, String text, [String? hint]) => Padding(
        padding: const EdgeInsets.only(top: 22, bottom: 8),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(text, style: display(context, size: 22)),
          if (hint != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(hint, style: TextStyle(color: context.c.muted, fontSize: 13))),
        ]),
      );

  Widget _bar(BuildContext context, num pct) {
    final c = context.c;
    return ClipRRect(
      borderRadius: BorderRadius.circular(99),
      child: LinearProgressIndicator(value: (pct / 100).clamp(0, 1).toDouble(), minHeight: 9, color: c.pink, backgroundColor: c.line),
    );
  }

  Widget _stat(BuildContext context, String label, String value, num now, num before) {
    final c = context.c;
    String? delta;
    Color? tone;
    if (before != 0) {
      final pct = (now - before) / before * 100;
      if (pct.abs() >= 0.5) {
        delta = '${pct > 0 ? '▲' : '▼'} ${percent(double.parse(pct.abs().toStringAsFixed(1)))} vs. anterior';
        tone = pct > 0 ? c.ok : c.bad;
      }
    }
    return Expanded(
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: TextStyle(color: c.muted, fontSize: 13)),
          const SizedBox(height: 4),
          Text(value, style: display(context, size: 26)),
          if (delta != null) Text(delta, style: TextStyle(color: tone, fontSize: 12, fontWeight: FontWeight.w700)),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = _m(d['overview']);
    final cur = _m(o['current']);
    final prev = _m(o['previous']);
    if (_n(cur['sessions']) == 0 && _n(cur['orders']) == 0) {
      return Padding(
        padding: const EdgeInsets.only(top: 32),
        child: Column(children: [
          Icon(Icons.insights_outlined, size: 44, color: c.muted),
          const SizedBox(height: 10),
          const Text('Ainda sem dados neste período', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
          const SizedBox(height: 6),
          Text('Quando os clientes abrirem o site ou o app, os números aparecem aqui.', textAlign: TextAlign.center, style: TextStyle(color: c.muted)),
        ]),
      );
    }
    final funnel = _m(d['funnel']);
    final steps = _l(funnel['steps']);
    final drop = funnel['biggest_drop'] == null ? null : _m(funnel['biggest_drop']);
    final recipes = _l(_m(d['recipes'])['list']);
    final pages = _l(_m(d['pages'])['list']);
    final sources = _m(d['sources']);
    final referrers = _l(sources['referrers']);
    final platforms = _l(sources['platforms']);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 12),
        Row(children: [
          _stat(context, 'Visitas', '${_n(cur['sessions'])}', _n(cur['sessions']), _n(prev['sessions'])),
          const SizedBox(width: 10),
          _stat(context, 'Pedidos', '${_n(cur['orders'])}', _n(cur['orders']), _n(prev['orders'])),
        ]),
        const SizedBox(height: 10),
        Row(children: [
          _stat(context, 'Páginas vistas', '${_n(cur['page_views'])}', _n(cur['page_views']), _n(prev['page_views'])),
          const SizedBox(width: 10),
          _stat(context, 'Conversão', percent(_n(cur['conversion_pct'])), _n(cur['conversion_pct']), _n(prev['conversion_pct'])),
        ]),
        const SizedBox(height: 8),
        Text(
          'Pedidos pagos: ${_n(cur['paid_orders'])} · Receita: ${money(_n(cur['revenue']))}. Cada visita é anônima: a mesma pessoa em dias diferentes conta mais de uma vez.',
          style: TextStyle(color: c.muted, fontSize: 12),
        ),
        _title(context, 'Até onde vão no pedido', drop == null ? null : 'Onde mais param: depois de "${drop['after']}" (${_n(drop['lost'])} visitas não seguiram).'),
        AppCard(
          child: Column(children: [
            for (var i = 0; i < steps.length; i++)
              Padding(
                padding: EdgeInsets.only(bottom: i == steps.length - 1 ? 0 : 14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${i + 1}. ${steps[i]['label']}', style: const TextStyle(fontWeight: FontWeight.w700))),
                    Text('${_n(steps[i]['sessions'])}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  ]),
                  if (i > 0) Text('${percent(_n(steps[i]['pct_of_top']))} do total · ${percent(_n(steps[i]['pct_of_previous']))} do passo anterior', style: TextStyle(color: c.muted, fontSize: 12)),
                  const SizedBox(height: 5),
                  _bar(context, _n(steps[i]['pct_of_top'])),
                ]),
              ),
          ]),
        ),
        _title(context, 'Receitas que mais chamam atenção', 'Vista = apareceu na tela; clique = tocou em Pedir; adicionado = entrou no pedido.'),
        chips<String>(context, _sorts, sort, onSort),
        const SizedBox(height: 8),
        if (recipes.isEmpty)
          Text('Nenhum item visto ou pedido neste período.', style: TextStyle(color: c.muted))
        else
          for (final r in recipes.take(15))
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: AppCard(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('${r['name']}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                  const SizedBox(height: 4),
                  Text(
                    '${_n(r['views'])} vistas · ${_n(r['clicks'])} cliques (${percent(_n(r['click_rate_pct']))}) · ${_n(r['adds'])} adicionados',
                    style: TextStyle(color: c.muted, fontSize: 13),
                  ),
                  Text('${_n(r['orders'])} pedidos · ${money(_n(r['revenue']))} · clique → pedido ${percent(_n(r['order_rate_pct']))}', style: const TextStyle(fontSize: 13)),
                ]),
              ),
            ),
        _title(context, 'Telas e páginas mais vistas'),
        AppCard(
          child: pages.isEmpty
              ? Text('Nada visto neste período.', style: TextStyle(color: c.muted))
              : Column(children: [
                  for (final p in pages.take(12))
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          Expanded(child: Text('${p['route']}', overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700))),
                          Text('${_n(p['views'])} vistas', style: TextStyle(color: c.muted, fontSize: 13)),
                        ]),
                        const SizedBox(height: 4),
                        _bar(context, _n(p['views']) / _n(pages.first['views']) * 100),
                      ]),
                    ),
                ]),
        ),
        _title(context, 'De onde vêm as visitas'),
        AppCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Origem da entrada (site)', style: TextStyle(color: c.muted, fontWeight: FontWeight.w700, fontSize: 13)),
            const SizedBox(height: 6),
            if (referrers.isEmpty) Text('Sem dados de origem.', style: TextStyle(color: c.muted)),
            for (final r in referrers)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(children: [
                  Expanded(child: Text(_referrers['${r['kind']}'] ?? '${r['kind']}', style: const TextStyle(fontWeight: FontWeight.w600))),
                  Text('${_n(r['sessions'])}', style: const TextStyle(fontWeight: FontWeight.w800)),
                ]),
              ),
            const SizedBox(height: 12),
            Text('Onde acessam', style: TextStyle(color: c.muted, fontWeight: FontWeight.w700, fontSize: 13)),
            const SizedBox(height: 6),
            if (platforms.isEmpty) Text('Sem dados.', style: TextStyle(color: c.muted)),
            for (final p in platforms)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(children: [
                  Expanded(child: Text(_platformNames['${p['platform']}'] ?? '${p['platform']}', style: const TextStyle(fontWeight: FontWeight.w600))),
                  Text('${_n(p['sessions'])} visitas', style: const TextStyle(fontWeight: FontWeight.w800)),
                ]),
              ),
          ]),
        ),
      ],
    );
  }
}
