import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Nome de cada categoria do histórico, como a dona lê.
const historyCategories = {
  'estoque': 'Estoque',
  'receita': 'Receitas',
  'preco': 'Preços',
  'cupom': 'Cupons',
  'pedido': 'Pedidos',
  'financeiro': 'Financeiro',
  'cardapio': 'Cardápio',
  'pix_presencial': 'Pix presencial',
};

/// Nome de cada ação.
const historyActions = {
  'criou': 'Criou',
  'editou': 'Editou',
  'excluiu': 'Excluiu',
  'aplicou': 'Aplicou',
  'ajustou': 'Ajustou',
  'ativou': 'Ativou',
  'desativou': 'Desativou',
  'importou': 'Importou',
  'cancelou': 'Cancelou',
  'devolveu': 'Devolveu',
  'pagou': 'Pagou',
  'gerou': 'Gerou',
};

/// Histórico: tudo o que mudou estoque, receitas, preços, cupons e pedidos, com quem fez, quando, de onde e o antes/depois.
/// Só leitura (o servidor não tem rota para editar nem apagar o histórico) e só para a dona.
class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key});
  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  static const _pageSize = 30;
  final _items = <Map<String, dynamic>>[];
  final _search = TextEditingController();
  String? _category, _action;
  int _days = 0; // 0 = todo o período
  int _total = 0, _page = 0; // _page: a última página já carregada
  bool _loading = true, _more = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  String get _query {
    final p = <String, String>{'page': '${_page + 1}', 'per_page': '$_pageSize'};
    if (_category != null) p['category'] = _category!;
    if (_action != null) p['action'] = _action!;
    if (_search.text.trim().isNotEmpty) p['q'] = _search.text.trim();
    if (_days > 0) p['from'] = DateTime.now().subtract(Duration(days: _days)).toUtc().toIso8601String();
    return Uri(queryParameters: p).query;
  }

  Future<void> _load({bool reset = false}) async {
    if (reset) {
      setState(() {
        _items.clear();
        _page = 0;
        _loading = true;
        _error = null;
      });
    } else {
      setState(() => _more = true);
    }
    try {
      final r = await context.read<Session>().ownerApi.get('/api/historico?$_query') as Map;
      if (!mounted) return;
      setState(() {
        _items.addAll([for (final e in r['items'] as List) Map<String, dynamic>.from(e as Map)]);
        _page++;
        _total = (r['total'] as num?)?.toInt() ?? _items.length;
        _loading = false;
        _more = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
        _more = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Scaffold(
      appBar: AppBar(title: const Text('Histórico')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _load(reset: true),
              decoration: InputDecoration(
                hintText: 'Buscar por nome, detalhe ou usuário',
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: _search.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: 'Limpar',
                        icon: const Icon(Icons.close_rounded),
                        onPressed: () {
                          _search.clear();
                          _load(reset: true);
                        },
                      ),
              ),
            ),
          ),
          SizedBox(
            height: 52,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              children: [
                for (final (label, days) in const [('Tudo', 0), ('Hoje', 1), ('7 dias', 7), ('30 dias', 30)])
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(label),
                      selected: _days == days,
                      onSelected: (_) {
                        _days = days;
                        _load(reset: true);
                      },
                    ),
                  ),
                _FilterMenu(
                  label: _category == null ? 'Categoria' : historyCategories[_category] ?? _category!,
                  active: _category != null,
                  options: historyCategories,
                  onPick: (v) {
                    _category = v;
                    _load(reset: true);
                  },
                ),
                const SizedBox(width: 8),
                _FilterMenu(
                  label: _action == null ? 'Ação' : historyActions[_action] ?? _action!,
                  active: _action != null,
                  options: historyActions,
                  onPick: (v) {
                    _action = v;
                    _load(reset: true);
                  },
                ),
              ],
            ),
          ),
          Expanded(child: _body(c)),
        ],
      ),
    );
  }

  Widget _body(AppColors c) {
    if (_loading) return Center(child: CircularProgressIndicator(color: c.pink));
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(_error!, textAlign: TextAlign.center, style: TextStyle(color: c.muted)),
            const SizedBox(height: 12),
            BusyButton(label: 'Tentar de novo', expand: false, onPressed: () => _load(reset: true)),
          ]),
        ),
      );
    }
    if (_items.isEmpty) {
      return Center(child: Text('Nada no histórico com esses filtros.', style: TextStyle(color: c.muted)));
    }
    return RefreshIndicator(
      color: c.pink,
      onRefresh: () => _load(reset: true),
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
        itemCount: _items.length + 1,
        itemBuilder: (_, i) {
          if (i < _items.length) return Padding(padding: const EdgeInsets.only(bottom: 10), child: _EntryCard(_items[i]));
          if (_items.length >= _total) {
            return Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Center(child: Text('$_total ${_total == 1 ? 'registro' : 'registros'}', style: TextStyle(color: c.muted))),
            );
          }
          return Padding(
            padding: const EdgeInsets.only(top: 6),
            child: BusyButton(label: 'Carregar mais', outlined: true, busy: _more, onPressed: () => _load()),
          );
        },
      ),
    );
  }
}

class _FilterMenu extends StatelessWidget {
  const _FilterMenu({required this.label, required this.active, required this.options, required this.onPick});
  final String label;
  final bool active;
  final Map<String, String> options;
  final void Function(String? value) onPick;
  @override
  Widget build(BuildContext context) => PopupMenuButton<String?>(
    onSelected: onPick,
    itemBuilder: (_) => [
      const PopupMenuItem<String?>(value: null, child: Text('Todas')),
      for (final e in options.entries) PopupMenuItem<String?>(value: e.key, child: Text(e.value)),
    ],
    child: Chip(
      label: Text(label),
      avatar: active ? const Icon(Icons.filter_alt_rounded, size: 18) : null,
      deleteIcon: const Icon(Icons.arrow_drop_down_rounded),
      onDeleted: null,
    ),
  );
}

final _when = DateFormat('dd/MM/yyyy HH:mm');

String _formatWhen(Object? iso) {
  final d = DateTime.tryParse('$iso');
  return d == null ? '' : _when.format(d.toLocal());
}

class _EntryCard extends StatelessWidget {
  const _EntryCard(this.e);
  final Map<String, dynamic> e;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final action = historyActions[e['action']] ?? '${e['action']}';
    final category = historyCategories[e['category']] ?? '${e['category']}';
    return AppCard(
      onTap: () => showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        showDragHandle: true,
        builder: (_) => HistoryDetail(e),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(20)),
              child: Text('$action · $category', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w800, fontSize: 12)),
            ),
            const Spacer(),
            Text(_formatWhen(e['occurred_at']), style: TextStyle(color: c.muted, fontSize: 12)),
          ]),
          const SizedBox(height: 8),
          Text('${e['details'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w700, height: 1.3)),
          const SizedBox(height: 4),
          Text('${e['user_name'] ?? ''} · ${e['location'] ?? ''}', style: TextStyle(color: c.muted, fontSize: 13)),
        ],
      ),
    );
  }
}

/// O registro inteiro, com o antes e o depois campo a campo.
class HistoryDetail extends StatelessWidget {
  const HistoryDetail(this.e, {super.key});
  final Map<String, dynamic> e;

  static Map<String, dynamic>? _asMap(Object? v) => v is Map ? Map<String, dynamic>.from(v) : null;

  static String _show(Object? v) => v == null ? '—' : (v is String ? v : jsonEncode(v));

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final before = _asMap(e['old_value']), after = _asMap(e['new_value']);
    final keys = <String>{...?before?.keys, ...?after?.keys}.toList()..sort();
    final changed = [for (final k in keys) if (_show(before?[k]) != _show(after?[k])) k];

    Widget row(String k, String v) => Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 108, child: Text(k, style: TextStyle(color: c.muted))),
        Expanded(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w600))),
      ]),
    );

    return SafeArea(
      child: ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        children: [
          Text('${e['details'] ?? ''}', style: display(context, size: 22)),
          const SizedBox(height: 12),
          row('Quando', _formatWhen(e['occurred_at'])),
          row('Quem', '${e['user_name'] ?? ''} (${e['role'] ?? ''})'),
          row('Ação', '${historyActions[e['action']] ?? e['action']} · ${historyCategories[e['category']] ?? e['category']}'),
          row('Onde', '${e['location'] ?? ''}'),
          row('Registro', '${e['record_type'] ?? ''} ${e['record_id'] ?? ''}'.trim()),
          row('Origem', '${e['ip'] ?? ''}'),
          const SizedBox(height: 10),
          if (changed.isNotEmpty) ...[
            Text('Antes → depois', style: display(context, size: 18)),
            const SizedBox(height: 8),
            for (final k in changed)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: AppCard(
                  padding: const EdgeInsets.all(12),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(k, style: const TextStyle(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 4),
                    Text('Antes: ${_show(before?[k])}', style: TextStyle(color: c.muted)),
                    Text('Depois: ${_show(after?[k])}'),
                  ]),
                ),
              ),
          ] else if (before == null && after != null) ...[
            Text('Valores gravados', style: display(context, size: 18)),
            const SizedBox(height: 8),
            for (final k in keys) row(k, _show(after[k])),
          ] else if (before != null && after == null) ...[
            Text('Valores que existiam', style: display(context, size: 18)),
            const SizedBox(height: 8),
            for (final k in keys) row(k, _show(before[k])),
          ],
          const SizedBox(height: 8),
          BusyButton(
            label: 'Fechar',
            outlined: true,
            onPressed: () => Navigator.of(context).pop(),
          ),
        ],
      ),
    );
  }
}
