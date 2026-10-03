import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

String _pct(double n) => '${(n * 10).round() / 10}%'.replaceAll('.', ',');

/// Avisos depois de um ingrediente mudar de preço: a margem caiu (preço fixado) ou o preço do cardápio vai subir. Some sozinho se não há alertas.
class PriceAlertsCard extends StatefulWidget {
  const PriceAlertsCard({super.key});
  @override
  State<PriceAlertsCard> createState() => _PriceAlertsCardState();
}

class _PriceAlertsCardState extends State<PriceAlertsCard> {
  List<PriceAlert> _list = const [];
  int? _busy;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    try {
      final r = await context.read<Session>().ownerApi.get('/api/price-alerts') as List;
      if (mounted) setState(() => _list = [for (final e in r) PriceAlert.fromJson(Map<String, dynamic>.from(e as Map))]);
    } catch (_) {
      // sem alertas ou sem rede: o cartão simplesmente não aparece
    }
  }

  Future<void> _act(PriceAlert a, bool apply) async {
    setState(() => _busy = a.id);
    try {
      await context.read<Session>().ownerApi.post('/api/price-alerts/${a.id}${apply ? '/apply' : ''}');
      Toast.ok(apply ? 'Preço de ${a.recipe} fixado em ${money(a.suggested ?? 0)}' : 'Alerta dispensado');
      await _load();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_list.isEmpty) return const SizedBox.shrink();
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: AppCard(
        color: c.warn.withValues(alpha: .08),
        borderColor: c.warn.withValues(alpha: .4),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Ingredientes mudaram de preço', style: TextStyle(fontWeight: FontWeight.w800)),
          for (final a in _list)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(
                  a.kind == 'margem' ? '${a.recipe}: margem caiu de ${_pct(a.before)} para ${_pct(a.after)}' : '${a.recipe}: preço sugerido sobe de ${money(a.before)} para ${money(a.after)}',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                Text(a.cause, style: TextStyle(color: c.muted, fontSize: 13)),
                Row(children: [
                  if (a.suggested != null)
                    BusyButton(label: 'Aplicar ${money(a.suggested!)}', expand: false, busy: _busy == a.id, onPressed: () => _act(a, true)),
                  TextButton(onPressed: _busy == a.id ? null : () => _act(a, false), child: const Text('Dispensar')),
                ]),
              ]),
            ),
        ]),
      ),
    );
  }
}
