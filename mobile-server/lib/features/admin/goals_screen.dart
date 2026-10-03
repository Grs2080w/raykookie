import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/pricing.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'goal_ring.dart';

class GoalsScreen extends StatefulWidget {
  const GoalsScreen({super.key});
  @override
  State<GoalsScreen> createState() => _GoalsScreenState();
}

class _GoalsScreenState extends State<GoalsScreen> {
  late DateTime _month = DateTime(DateTime.now().year, DateTime.now().month);
  String get _key =>
      '${_month.year.toString().padLeft(4, '0')}-${_month.month.toString().padLeft(2, '0')}-01';

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      appBar: AppBar(title: const Text('Metas de venda')),
      body: Loader<(Goal, MonthStats)>(
        key: ValueKey(_key),
        load: () async {
          final r =
              await api.get('/api/goals?month=$_key') as Map<String, dynamic>;
          return (Goal.fromJson(r['goal']), MonthStats.fromJson(r['stats']));
        },
        builder: (context, data, reload) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  onPressed: () => setState(
                    () => _month = DateTime(_month.year, _month.month - 1),
                  ),
                  icon: const Icon(Icons.chevron_left_rounded),
                  tooltip: 'Mês anterior',
                ),
                Flexible(
                  child: Text(
                    '${monthNames[_month.month - 1][0].toUpperCase()}${monthNames[_month.month - 1].substring(1)} de ${_month.year}',
                    textAlign: TextAlign.center,
                    style: display(context, size: 24),
                  ),
                ),
                IconButton(
                  onPressed: () => setState(
                    () => _month = DateTime(_month.year, _month.month + 1),
                  ),
                  icon: const Icon(Icons.chevron_right_rounded),
                  tooltip: 'Próximo mês',
                ),
              ],
            ),
            const SizedBox(height: 8),
            _GoalsBody(
              key: ValueKey(_key),
              month: _key,
              goal: data.$1,
              stats: data.$2,
              onSaved: reload,
            ),
          ],
        ),
      ),
    );
  }
}

class _GoalsBody extends StatefulWidget {
  const _GoalsBody({
    super.key,
    required this.month,
    required this.goal,
    required this.stats,
    required this.onSaved,
  });
  final String month;
  final Goal goal;
  final MonthStats stats;
  final Future<void> Function() onSaved;
  @override
  State<_GoalsBody> createState() => _GoalsBodyState();
}

class _GoalsBodyState extends State<_GoalsBody> {
  late final _revenue = TextEditingController(
    text: widget.goal.revenue > 0 ? fieldNum(widget.goal.revenue) : '',
  );
  late final _orders = TextEditingController(
    text: widget.goal.orders > 0 ? '${widget.goal.orders}' : '',
  );
  late final _ticket = TextEditingController(
    text: widget.goal.avgTicket > 0 ? fieldNum(widget.goal.avgTicket) : '',
  );
  late final _newCust = TextEditingController(
    text: widget.goal.newCustomers > 0 ? '${widget.goal.newCustomers}' : '',
  );
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_revenue, _orders, _ticket, _newCust]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().ownerApi.put('/api/goals', {
        'month': widget.month,
        'revenue': _revenue.text,
        'orders': _orders.text,
        'new_customers': _newCust.text,
        'avg_ticket': _ticket.text,
      });
      Toast.ok('Metas salvas');
      await widget.onSaved();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  (String, String) _statusOf(double target, double pct) {
    if (target <= 0) return ('neutral', 'Sem meta definida');
    if (pct >= 100) return ('ok', 'Meta batida');
    if (pct >= 80) return ('warn', 'Quase lá');
    return ('bad', 'Abaixo da meta');
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final s = widget.stats;
    final targets = (
      revenue: parseNum(_revenue.text),
      orders: parseNum(_orders.text),
      ticket: parseNum(_ticket.text),
      customers: parseNum(_newCust.text),
    );
    final main = goalProgress(targets.revenue, s.revenue);
    final items = [
      (
        'Faturamento',
        Icons.attach_money_rounded,
        targets.revenue,
        s.revenue,
        money,
      ),
      (
        'Pedidos',
        Icons.receipt_long_rounded,
        targets.orders,
        s.orders.toDouble(),
        (num v) => number(v),
      ),
      ('Ticket médio', Icons.sell_rounded, targets.ticket, s.avgTicket, money),
      (
        'Clientes novos',
        Icons.person_add_alt_1_rounded,
        targets.customers,
        s.newCustomers.toDouble(),
        (num v) => number(v),
      ),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        AppCard(
          padding: const EdgeInsets.all(20),
          child: Column(
            children: [
              GoalRing(pct: main.pct, size: 170),
              const SizedBox(height: 14),
              Text('Faturado', style: TextStyle(color: c.muted)),
              Text(
                money(s.revenue),
                style: display(context, size: 32, color: c.pinkStrong),
              ),
              const SizedBox(height: 8),
              Text('Lucro total', style: TextStyle(color: c.muted)),
              Text(
                money(s.profit),
                style: display(context, size: 24, color: c.pinkStrong),
              ),
              const SizedBox(height: 2),
              Text(
                targets.revenue > 0
                    ? (main.diff >= 0
                          ? '${money(main.diff)} acima da meta'
                          : 'Faltam ${money(-main.diff)}')
                    : 'Defina a meta de faturamento abaixo',
                style: TextStyle(color: c.muted, fontSize: 13),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        AppCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Suas metas do mês', style: display(context, size: 22)),
              const SizedBox(height: 14),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: NumberField(
                      label: 'Faturamento (R\$)',
                      controller: _revenue,
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: NumberField(
                      label: 'Pedidos',
                      controller: _orders,
                      integer: true,
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: NumberField(
                      label: 'Ticket médio (R\$)',
                      controller: _ticket,
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: NumberField(
                      label: 'Clientes novos',
                      controller: _newCust,
                      integer: true,
                      onChanged: (_) => setState(() {}),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              BusyButton(label: 'Salvar metas', busy: _busy, onPressed: _save),
            ],
          ),
        ),
        const SizedBox(height: 14),
        for (final (label, icon, target, actual, fmt) in items) ...[
          Builder(
            builder: (context) {
              final g = goalProgress(target, actual);
              final (tone, text) = _statusOf(target, g.pct);
              return Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(
                              color: c.pinkTint,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Icon(icon, color: c.pinkStrong, size: 19),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              label,
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                          AppBadge(text, tone: tone),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(fmt(actual), style: display(context, size: 28)),
                          const SizedBox(width: 8),
                          Padding(
                            padding: const EdgeInsets.only(bottom: 4),
                            child: Text(
                              'de ${target > 0 ? fmt(target) : '—'}',
                              style: TextStyle(color: c.muted, fontSize: 13),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(99),
                        child: LinearProgressIndicator(
                          value: (g.pct / 100).clamp(0, 1),
                          minHeight: 10,
                          backgroundColor: c.surface2,
                          color: c.pink,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        target > 0
                            ? '${g.pct.round()}% atingido · diferença ${g.diff >= 0 ? '+' : '−'}${fmt(g.diff.abs())}'
                            : 'Sem meta definida',
                        style: TextStyle(color: c.muted, fontSize: 12),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ],
    );
  }
}
