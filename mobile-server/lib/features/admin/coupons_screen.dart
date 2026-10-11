import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Cupons de desconto: cada um tem um código, uma porcentagem (até 90%) e, se quiser, um valor mínimo do pedido
/// ("a partir de" ou "acima de"). O cliente digita o código no último passo do pedido.
class CouponsScreen extends StatefulWidget {
  const CouponsScreen({super.key});
  @override
  State<CouponsScreen> createState() => _CouponsScreenState();
}

class _CouponsScreenState extends State<CouponsScreen> {
  List<Map<String, dynamic>>? _list;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _set(Object? list) => setState(() => _list = [for (final e in list as List) Map<String, dynamic>.from(e as Map)]);

  Future<void> _load() async {
    try {
      final r = await context.read<Session>().ownerApi.get('/api/coupons');
      if (mounted) _set(r);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _create() async {
    final r = await showDialog<Map<String, dynamic>>(context: context, builder: (_) => const _CouponDialog());
    if (r == null || !mounted) return;
    try {
      _set(await context.read<Session>().ownerApi.post('/api/coupons', r));
      Toast.ok('Cupom criado');
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  Future<void> _toggle(Map<String, dynamic> c, bool active) async {
    try {
      _set(await context.read<Session>().ownerApi.put('/api/coupons/${c['id']}', {
        'percent': c['percent'], 'min_total': c['min_total'], 'min_inclusive': c['min_inclusive'], 'active': active,
      }));
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  Future<void> _delete(Map<String, dynamic> c) async {
    if (!await confirmDialog(context, title: 'Excluir o cupom ${c['code']}?', message: 'Quem tiver o código não consegue mais usar.')) return;
    if (!mounted) return;
    try {
      _set(await context.read<Session>().ownerApi.delete('/api/coupons/${c['id']}'));
      Toast.ok('Cupom excluído');
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  String _rule(Map<String, dynamic> c) {
    final min = (c['min_total'] as num?)?.toDouble();
    if (min == null) return 'Vale para qualquer pedido';
    return c['min_inclusive'] == false ? 'Pedidos acima de ${money(min)}' : 'Pedidos a partir de ${money(min)}';
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final list = _list;
    return Scaffold(
      appBar: AppBar(title: const Text('Cupons')),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('coupon_new'),
        onPressed: _create,
        icon: const Icon(Icons.add),
        label: const Text('Novo cupom'),
      ),
      body: _error != null
          ? Center(child: Text(_error!, style: TextStyle(color: c.bad)))
          : list == null
              ? const Center(child: CircularProgressIndicator())
              : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 96), children: [
                  Text('O cliente digita o código no último passo do pedido. O desconto é calculado sobre o total dos itens.', style: TextStyle(color: c.muted)),
                  const SizedBox(height: 12),
                  if (list.isEmpty)
                    Padding(padding: const EdgeInsets.only(top: 24), child: Center(child: Text('Nenhum cupom ainda.', style: TextStyle(color: c.muted)))),
                  for (final coupon in list)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: AppCard(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Row(children: [
                            Expanded(child: SelectableText('${coupon['code']}', style: display(context, size: 22))),
                            IconButton(
                              tooltip: 'Copiar código',
                              onPressed: () {
                                Clipboard.setData(ClipboardData(text: '${coupon['code']}'));
                                Toast.ok('Código copiado');
                              },
                              icon: const Icon(Icons.copy_rounded),
                            ),
                            Switch(value: coupon['active'] == true, activeTrackColor: c.pink, onChanged: (v) => _toggle(coupon, v)),
                          ]),
                          Text('-${fieldNum((coupon['percent'] as num).toDouble())}% · ${_rule(coupon)}', style: const TextStyle(fontWeight: FontWeight.w700)),
                          Row(children: [
                            Expanded(
                              child: Text(
                                '${coupon['active'] == true ? 'Ativo' : 'Desativado'} · usado ${coupon['uses']} ${coupon['uses'] == 1 ? 'vez' : 'vezes'}',
                                style: TextStyle(color: c.muted, fontSize: 13),
                              ),
                            ),
                            if (coupon['uses'] == 0) TextButton(onPressed: () => _delete(coupon), child: const Text('Excluir')),
                          ]),
                        ]),
                      ),
                    ),
                ]),
    );
  }
}

/// "Novo cupom": código (em branco = gerado), porcentagem e valor mínimo opcional.
class _CouponDialog extends StatefulWidget {
  const _CouponDialog();
  @override
  State<_CouponDialog> createState() => _CouponDialogState();
}

class _CouponDialogState extends State<_CouponDialog> {
  final _code = TextEditingController();
  final _percent = TextEditingController();
  final _min = TextEditingController();
  bool _inclusive = true;

  @override
  void dispose() {
    for (final c in [_code, _percent, _min]) {
      c.dispose();
    }
    super.dispose();
  }

  void _ok() {
    final pct = parseNum(_percent.text);
    if (pct < 1 || pct > 90) return Toast.error('O desconto vai de 1% a 90%.');
    Navigator.pop(context, {
      'code': _code.text.trim(),
      'percent': pct,
      'min_total': parseNum(_min.text) > 0 ? parseNum(_min.text) : null,
      'min_inclusive': _inclusive,
    });
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Novo cupom'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            AppTextField(
              key: const Key('coupon_code'),
              label: 'Código (opcional)',
              controller: _code,
              hint: 'Ex.: NATAL20',
              helper: 'Em branco, o app gera um código.',
              maxLength: 20,
              capitalization: TextCapitalization.characters,
            ),
            const SizedBox(height: 12),
            NumberField(key: const Key('coupon_percent'), label: 'Desconto (%)', controller: _percent, hint: 'Ex.: 15', integer: true),
            const SizedBox(height: 12),
            NumberField(key: const Key('coupon_min'), label: 'Valor mínimo do pedido (R\$, opcional)', controller: _min, hint: 'Ex.: 50'),
            const SizedBox(height: 8),
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(value: true, label: Text('A partir de')),
                ButtonSegment(value: false, label: Text('Acima de')),
              ],
              selected: {_inclusive},
              onSelectionChanged: (v) => setState(() => _inclusive = v.first),
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
          FilledButton(key: const Key('coupon_create'), onPressed: _ok, child: const Text('Criar')),
        ],
      );
}
