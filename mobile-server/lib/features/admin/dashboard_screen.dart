import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/pricing.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'goal_ring.dart';
import 'price_alerts_card.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Loader<Dashboard>(
      load: () async => Dashboard.fromJson(
        await api.get('/api/dashboard') as Map<String, dynamic>,
      ),
      builder: (context, d, reload) {
        final c = context.c;
        final now = DateTime.now();
        final g = goalProgress(d.goal.revenue, d.stats.revenue);
        Widget head(
          IconData icon,
          Color color,
          String title,
          String action,
          String route,
        ) => Row(
          children: [
            Icon(icon, size: 20, color: color),
            const SizedBox(width: 8),
            Expanded(child: Text(title, style: display(context, size: 20))),
            TextButton(onPressed: () => context.go(route), child: Text(action)),
          ],
        );
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 12),
            UpdateBanner(api: api, app: 'server'),
            const PriceAlertsCard(),
            if (d.refundsWaiting > 0 || d.refundsFailed > 0 || d.refundsPending > 0)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: AppCard(
                  onTap: () => context.push('/painel/devolucoes'),
                  color: c.warn.withValues(alpha: .1),
                  borderColor: c.warn.withValues(alpha: .4),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(
                      d.refundsWaiting > 0
                          ? d.refundsWaiting == 1 ? '1 devolução aguardando sua decisão' : '${d.refundsWaiting} devoluções aguardando sua decisão'
                          : d.refundsFailed > 0
                              ? 'Devolução parada'
                              : 'Devoluções em processamento',
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    Text(
                      '${d.refundsFailed > 0 ? 'Uma devolução não saiu (provavelmente falta saldo no Mercado Pago). ' : ''}${d.refundsPending > 0 ? 'Mantenha ${money(d.refundsPending)} disponíveis no Mercado Pago.' : 'Toque para ver.'}',
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                  ]),
                ),
              ),
            Text('Olá, ${d.username}', style: display(context, size: 34)),
            Text(
              '${now.day} de ${monthNames[now.month - 1]}. Aqui está o resumo do seu mês.',
              style: TextStyle(color: c.muted),
            ),
            const SizedBox(height: 18),
            if (d.empty) ...[
              AppCard(
                color: c.pinkTint,
                borderColor: c.pink.withValues(alpha: 0.4),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Comece pelo estoque',
                      style: display(context, size: 20, color: c.pinkStrong),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Cadastre seus ingredientes com o preço da embalagem, depois monte uma receita para ver o preço de venda.',
                      style: TextStyle(
                        color: c.pinkStrong.withValues(alpha: 0.85),
                      ),
                    ),
                    const SizedBox(height: 12),
                    BusyButton(
                      label: 'Cadastrar ingredientes',
                      icon: Icons.arrow_forward_rounded,
                      expand: false,
                      onPressed: () => context.go('/painel/estoque'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],
            AppCard(
              onTap: () => context.push('/painel/metas'),
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Row(
                    children: [
                      GoalRing(pct: g.pct, size: 130),
                      const SizedBox(width: 18),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'Faturamento de ${monthNames[now.month - 1]}',
                              style: TextStyle(color: c.muted, fontSize: 13),
                            ),
                            Text(
                              money(d.stats.revenue),
                              style: display(
                                context,
                                size: 30,
                                color: c.pinkStrong,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Text(
                              'Lucro total',
                              style: TextStyle(color: c.muted, fontSize: 13),
                            ),
                            Text(
                              money(d.stats.profit),
                              style: display(
                                context,
                                size: 22,
                                color: c.pinkStrong,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              d.goal.revenue > 0
                                  ? 'Meta de ${money(d.goal.revenue)}'
                                  : 'Você ainda não definiu a meta do mês',
                              style: TextStyle(color: c.muted, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 18),
                  Wrap(
                    spacing: 28,
                    runSpacing: 12,
                    children: [
                      for (final (label, value) in [
                        ('Pedidos', '${d.stats.orders}'),
                        ('Ticket médio', money(d.stats.avgTicket)),
                        ('A receber', money(d.receivable)),
                      ])
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              label,
                              style: TextStyle(color: c.muted, fontSize: 13),
                            ),
                            Text(value, style: display(context, size: 22)),
                          ],
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  head(
                    Icons.event_outlined,
                    c.pink,
                    'Próximas entregas',
                    'Ver pedidos',
                    '/painel/pedidos',
                  ),
                  if (d.upcoming.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        'Nenhum pedido em andamento.',
                        style: TextStyle(color: c.muted),
                      ),
                    )
                  else
                    for (final o in d.upcoming)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 54,
                              child: Text(
                                o.deliveryDate != null
                                    ? dateBR(o.deliveryDate).substring(0, 5)
                                    : 'sem data',
                                style: TextStyle(
                                  color: c.pinkStrong,
                                  fontWeight: FontWeight.w800,
                                  fontSize: 13,
                                ),
                              ),
                            ),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    o.customer,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  Text(
                                    '${number(o.qty)}× ${o.product}',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: c.muted,
                                      fontSize: 13,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(width: 8),
                            _statusBadge(o.status),
                          ],
                        ),
                      ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  head(
                    Icons.warning_amber_rounded,
                    c.warn,
                    'Precisam de reposição',
                    'Ver estoque',
                    '/painel/estoque',
                  ),
                  if (d.low.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        d.ingredientCount > 0
                            ? 'Tudo em dia. Nenhum ingrediente abaixo do mínimo.'
                            : 'Cadastre ingredientes para acompanhar o estoque.',
                        style: TextStyle(color: c.muted),
                      ),
                    )
                  else
                    for (final i in d.low)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                i.name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                            AppBadge(
                              i.stockQty <= 0
                                  ? 'Acabou'
                                  : '${number(i.stockQty)} restante${i.stockQty == 1 ? '' : 's'}',
                              tone: i.stockQty <= 0 ? 'bad' : 'warn',
                            ),
                          ],
                        ),
                      ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  head(
                    Icons.trending_up_rounded,
                    c.ok,
                    'Receitas mais lucrativas',
                    'Ver receitas',
                    '/painel/receitas',
                  ),
                  if (d.best.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        'Crie receitas para ver o ranking de margem.',
                        style: TextStyle(color: c.muted),
                      ),
                    )
                  else
                    for (final r in d.best)
                      InkWell(
                        onTap: () => context.push('/painel/receitas/${r.id}'),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  r.name,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                              Text(
                                money(r.pricing?.rounded ?? 0),
                                style: TextStyle(color: c.muted, fontSize: 13),
                              ),
                              SizedBox(
                                width: 62,
                                child: Text(
                                  percent(r.pricing?.realMarginPct ?? 0),
                                  textAlign: TextAlign.right,
                                  style: TextStyle(
                                    color: c.ok,
                                    fontWeight: FontWeight.w800,
                                    fontSize: 13,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

Widget _statusBadge(String status) {
  final s = orderStatus.where((e) => e.$1 == status).firstOrNull;
  return AppBadge(s?.$2 ?? status, tone: s?.$3 ?? 'neutral');
}
