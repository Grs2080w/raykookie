import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/refund.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/customer_push.dart';
import 'brand.dart';
import 'card_brands.dart';
import 'card_fields.dart';
import 'three_ds_challenge.dart';
import 'feedback_card.dart';

/// Pedidos já feitos pelo cliente, com a situação de cada um.
class CustomerOrdersScreen extends StatelessWidget {
  const CustomerOrdersScreen({super.key, this.justCreated, this.focus});
  final String? justCreated;
  final String? focus; // id do pedido aberto por um aviso: fica em destaque e a tela rola até ele

  @override
  Widget build(BuildContext context) {
    final session = context.watch<Session>();
    return BrandScope(
      child: Builder(
        builder: (context) {
          final c = context.c;
          final first = (session.profile?.name ?? '').split(' ').first;
          return Scaffold(
            appBar: BrandAppBar(
              showBack: context.canPop(),
              actions: [
                TextButton(
                  onPressed: () => context.push('/cliente/conta'),
                  style: TextButton.styleFrom(foregroundColor: c.ink),
                  child: const Text('Meus dados'),
                ),
                TextButton.icon(
                  onPressed: () async {
                    await context.read<CustomerPush>().unregister(); // antes de sair: precisa da sessão
                    await session.logoutCustomer();
                    if (context.mounted) context.go('/');
                  },
                  icon: const Icon(Icons.logout_rounded, size: 18),
                  label: const Text('Sair'),
                  style: TextButton.styleFrom(foregroundColor: c.ink),
                ),
              ],
            ),
            bottomNavigationBar: Material(
              color: c.surface,
              elevation: 6,
              child: SafeArea(
                top: false,
                child: Container(
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
                  decoration: BoxDecoration(
                    border: Border(top: BorderSide(color: c.line)),
                  ),
                  // Center com heightFactor: a barra tem a altura do botão (um Align comum ocuparia a tela toda)
                  child: Center(
                    heightFactor: 1,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 720),
                      child: OutlinedButton.icon(
                        onPressed: () => context.push('/cliente/pedidos/novo'),
                        icon: const Icon(Icons.add_rounded),
                        label: const Text('Novo pedido'),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: c.pinkStrong,
                          side: BorderSide(color: c.pink, width: 2),
                          minimumSize: const Size(double.infinity, 52),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            body: Centered(
              child: Loader<List<ShopOrder>>(
                // recarrega quando a rota muda (aviso tocado com a tela aberta) ou quando chega um aviso com o app aberto
                key: ValueKey('$justCreated|$focus|${context.watch<CustomerPush>().received}'),
                load: () async => [
                  for (final e in await session.customerApi.get(
                    '/api/cliente/pedidos',
                  ) as List)
                    ShopOrder.fromJson(e),
                ],
                builder: (context, orders, reload) {
                  final created = justCreated == null
                      ? null
                      : orders
                            .where((o) => o.id.toString() == justCreated)
                            .firstOrNull;
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const SizedBox(height: 12),
                      Text('Meus pedidos', style: display(context, size: 32)),
                      Text(
                        first.isEmpty
                            ? 'Acompanhe tudo por aqui.'
                            : 'Olá, $first! Acompanhe tudo por aqui.',
                        style: TextStyle(color: c.muted),
                      ),
                      if (created != null) ...[
                        const SizedBox(height: 16),
                        Container(
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            color: c.ok.withValues(alpha: 0.1),
                            borderRadius: BorderRadius.circular(18),
                            border: Border.all(
                              color: c.ok.withValues(alpha: 0.3),
                            ),
                          ),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Icon(
                                Icons.check_circle_outline_rounded,
                                color: c.ok,
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      'Pedido #${created.code} recebido!',
                                      style: const TextStyle(
                                        fontWeight: FontWeight.w800,
                                      ),
                                    ),
                                    Text(
                                      '${created.pixCode.isNotEmpty
                                          ? 'Pague com o Pix abaixo. '
                                          : created.paymentMethod == 'pix'
                                          ? 'Estamos gerando o seu Pix; se ele não aparecer aqui, falamos com você pelo WhatsApp. '
                                          : ''}Todo o acompanhamento do pedido é feito aqui, pelo app. Só entraremos em contato pelo WhatsApp se for necessário.',
                                      style: TextStyle(
                                        color: c.muted,
                                        fontSize: 13.5,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                      const _PushOffer(),
                      const SizedBox(height: 18),
                      if (orders.isEmpty)
                        EmptyState(
                          icon: Icons.shopping_bag_outlined,
                          title: 'Você ainda não fez pedidos',
                          text: 'Escolha o que quer no cardápio e diga para quando precisa. Leva só alguns minutos.',
                          action: BusyButton(
                            label: 'Fazer meu primeiro pedido',
                            icon: Icons.add_rounded,
                            expand: false,
                            onPressed: () =>
                                context.push('/cliente/pedidos/novo'),
                          ),
                        )
                      else
                        for (final o in orders)
                          _OrderCard(o, onChanged: reload, focused: o.id.toString() == focus),
                      const SizedBox(height: 18),
                      const FeedbackCard(),
                    ],
                  );
                },
              ),
            ),
          );
        },
      ),
    );
  }
}

class _OrderCard extends StatefulWidget {
  const _OrderCard(this.o, {required this.onChanged, this.focused = false});
  final ShopOrder o;
  final Future<void> Function() onChanged;
  final bool focused;
  @override
  State<_OrderCard> createState() => _OrderCardState();
}

class _OrderCardState extends State<_OrderCard> {
  final _key = GlobalKey();

  @override
  void initState() {
    super.initState();
    if (widget.focused) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final ctx = _key.currentContext;
        if (ctx != null) Scrollable.ensureVisible(ctx, duration: const Duration(milliseconds: 300), alignment: 0.05);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final o = widget.o;
    final onChanged = widget.onChanged;
    final c = context.c;
    final st = customerStatus[o.status] ?? customerStatus['novo']!;
    final pay = paymentLabel(o.paymentMethod);
    final win =
        timeWindows
            .where((w) => w.id == o.timeWindow)
            .map((w) => w.label)
            .firstOrNull ??
        o.timeWindow;
    final created = DateTime.tryParse(o.createdAt)?.toLocal();
    final where = o.fulfillment == 'entrega'
        ? 'Entrega: ${o.street}, ${o.number}${o.complement.isNotEmpty ? ' (${o.complement})' : ''} · ${o.district}, ${o.city}'
        : (o.pickupAddress.isNotEmpty ? 'Retirada: ${o.pickupAddress}' : 'Retirada no local');
    Widget line(IconData i, String t) => Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(i, size: 16, color: c.muted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(t, style: TextStyle(color: c.muted, fontSize: 13.5)),
          ),
        ],
      ),
    );
    return Padding(
      key: _key,
      padding: const EdgeInsets.only(bottom: 14),
      child: AppCard(
        borderColor: widget.focused ? c.pink : null,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Pedido #${o.code}',
                        style: display(context, size: 22),
                      ),
                      if (created != null)
                        Text(
                          'Feito em ${dateBR(todayIso(created))}',
                          style: TextStyle(color: c.muted, fontSize: 13),
                        ),
                    ],
                  ),
                ),
                if (_refundBadge(o) case (final label, final tone))
                  AppBadge(label, tone: tone)
                else if ((o.paymentMethod == 'pix' || o.paymentMethod == 'cartao') && o.status != 'cancelado')
                  AppBadge(
                    o.paid ? (o.paymentMethod == 'cartao' ? 'Pago' : 'Pix pago') : 'Aguardando pagamento',
                    tone: o.paid ? 'ok' : 'warn',
                  )
                else
                  AppBadge(st.$1, tone: st.$2),
              ],
            ),
            const SizedBox(height: 14),
            _Timeline(o.status, closedByRefund: o.cancel?.fullRefund ?? false),
            if (o.cancel != null) _CancelInfo(o.cancel!),
            if (o.paid && (o.status == 'novo' || o.status == 'producao'))
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(top: 14),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: c.ok.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: 'Pagamento confirmado. ',
                        style: TextStyle(
                          color: c.ok,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      TextSpan(
                        text:
                            'Estamos preparando o seu pedido para ${dateBR(o.scheduledFor)}.',
                      ),
                    ],
                  ),
                  style: const TextStyle(fontSize: 14, height: 1.4),
                ),
              ),
            const SizedBox(height: 12),
            Divider(color: c.line),
            for (final i in o.items)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  children: [
                    Expanded(
                      child: Text.rich(
                        TextSpan(
                          children: [
                            TextSpan(
                              text: '${i.qty}× ',
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            TextSpan(text: i.name),
                          ],
                        ),
                      ),
                    ),
                    Text(
                      money(i.qty * i.unitPrice),
                      style: TextStyle(color: c.muted),
                    ),
                  ],
                ),
              ),
            Divider(color: c.line),
            if (o.discountTotal > 0)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Row(children: [
                  Expanded(child: Text('Cupom ${o.couponCode}', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700))),
                  Text('-${money(o.discountTotal)}', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700)),
                ]),
              ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Total',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  Text(money(o.total), style: display(context, size: 24)),
                ],
              ),
            ),
            line(Icons.event_outlined, '${dateBR(o.scheduledFor)} · $win'),
            line(Icons.place_outlined, where),
            line(
              Icons.credit_card_outlined,
              'Pagamento: $pay${o.paymentMethod == 'pix' || o.paymentMethod == 'cartao' ? ' · ${_payState(o)}' : ''}',
            ),
            if (o.hasPix && o.status != 'cancelado')
              _PixBox(o, onChanged: onChanged),
            if (o.paymentMethod == 'cartao' && !o.paid && o.status != 'cancelado')
              o.cardInReview ? const _CardInReviewBox() : _CardRetryBox(o, onChanged: onChanged),
            if (o.canCancel)
              Padding(
                padding: const EdgeInsets.only(top: 14),
                child: _CancelButton(o, onChanged: onChanged),
              )
            else if (o.refundMode == 'solicitacao' && o.refunds.isEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  'A produção já começou, então o cancelamento é feito por um pedido de devolução, que passa por análise.',
                  style: TextStyle(color: c.muted, fontSize: 13),
                ),
              ),
            _RefundBox(o, onChanged: onChanged),
            if (o.status == 'entregue' && o.items.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 14),
                child: BusyButton(
                  label: 'Pedir de novo',
                  icon: Icons.replay_rounded,
                  onPressed: () => context.push(
                    '/cliente/pedidos/novo?item=${o.items.first.id}',
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

const _steps = [
  ('novo', 'Recebido'),
  ('producao', 'Em preparo'),
  ('pronto', 'Pronto'),
  ('entregue', 'Entregue'),
];

/// Linha do tempo do pedido: recebido → em preparo → pronto → entregue (ou cancelado).
class _Timeline extends StatelessWidget {
  const _Timeline(this.status, {this.closedByRefund = false});
  final String status;
  final bool closedByRefund; // todo o valor foi devolvido: o pedido acabou, ninguém "cancelou"
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    if (status == 'cancelado') {
      final color = closedByRefund ? c.muted : c.bad;
      return Row(
        children: [
          Icon(closedByRefund ? Icons.check_rounded : Icons.close_rounded, size: 18, color: color),
          const SizedBox(width: 6),
          Text(
            closedByRefund ? 'Pedido encerrado' : 'Pedido cancelado',
            style: TextStyle(color: color, fontWeight: FontWeight.w700),
          ),
        ],
      );
    }
    final at = _steps
        .indexWhere((s) => s.$1 == status)
        .clamp(0, _steps.length - 1);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (i, s) in _steps.indexed)
          Expanded(
            child: Column(
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Container(
                        height: 2,
                        color: i == 0
                            ? Colors.transparent
                            : (i <= at ? c.pink : c.line),
                      ),
                    ),
                    Container(
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: i < at || status == 'entregue'
                            ? c.pink
                            : c.surface,
                        border: Border.all(
                          color: i <= at ? c.pink : c.line,
                          width: 2,
                        ),
                      ),
                      child: i < at || status == 'entregue'
                          ? Icon(Icons.check_rounded, size: 14, color: c.onPink)
                          : (i == at
                                ? Center(
                                    child: Container(
                                      width: 8,
                                      height: 8,
                                      decoration: BoxDecoration(
                                        shape: BoxShape.circle,
                                        color: c.pink,
                                      ),
                                    ),
                                  )
                                : null),
                    ),
                    Expanded(
                      child: Container(
                        height: 2,
                        color: i == _steps.length - 1
                            ? Colors.transparent
                            : (i < at ? c.pink : c.line),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  s.$2,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11.5,
                    fontWeight: i <= at ? FontWeight.w700 : FontWeight.w400,
                    color: i <= at ? c.ink : c.muted,
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// "28:41" (ou "1 h 05 min", se faltar mais de uma hora).
/// Pago com cartão: a devolução é estorno no cartão (o Mercado Pago devolve na fatura), não Pix.
bool paidByCard(ShopOrder o) => o.paymentMethod == 'cartao';

String pixLeft(int sec) {
  final s = sec < 0 ? 0 : sec;
  if (s >= 3600) {
    return '${s ~/ 3600} h ${((s % 3600) ~/ 60).toString().padLeft(2, '0')} min';
  }
  return '${(s ~/ 60).toString().padLeft(2, '0')}:${(s % 60).toString().padLeft(2, '0')}';
}

/// Pix copia e cola do pedido, com a contagem regressiva da validade. Vencido, mostra "Gerar novo Pix".
/// (O pedido só vira "pago" quando o Mercado Pago confirma.)
class _PixBox extends StatefulWidget {
  const _PixBox(this.o, {required this.onChanged});
  final ShopOrder o;
  final Future<void> Function() onChanged;
  @override
  State<_PixBox> createState() => _PixBoxState();
}

class _PixBoxState extends State<_PixBox> {
  Timer? _timer;
  late int _left;
  late DateTime _t0;
  int _ticks = 0;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void didUpdateWidget(_PixBox old) {
    super.didUpdateWidget(old);
    // chegou um Pix novo (ou a lista foi recarregada): recomeça a contagem
    if (old.o.pixCode != widget.o.pixCode ||
        old.o.pixExpiresIn != widget.o.pixExpiresIn ||
        old.o.pixExpired != widget.o.pixExpired) {
      _start();
    }
  }

  /// O servidor manda os segundos que faltam: a contagem não depende do relógio do celular.
  void _start() {
    _timer?.cancel();
    _t0 = DateTime.now();
    _ticks = 0;
    _left = widget.o.pixExpired ? 0 : widget.o.pixExpiresIn;
    if (_left > 0) {
      _timer = Timer.periodic(const Duration(seconds: 1), (t) {
        // vale o maior entre os "ticks" e o relógio: com o app em segundo plano os ticks param, o relógio não
        _ticks++;
        final elapsed = DateTime.now().difference(_t0).inSeconds;
        final l = widget.o.pixExpiresIn - (elapsed > _ticks ? elapsed : _ticks);
        if (!mounted) return;
        setState(() => _left = l < 0 ? 0 : l);
        if (l <= 0) t.cancel();
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  bool get _gone =>
      widget.o.pixExpired || _left <= 0 || widget.o.pixCode.isEmpty;

  Future<void> _renew() async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().customerApi.post(
        '/api/cliente/pedidos/${widget.o.id}/pix',
      );
      await widget.onChanged();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = widget.o;
    if (_gone) {
      return Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 14),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: c.surface2,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: c.line),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.schedule_rounded, size: 18, color: c.muted),
                const SizedBox(width: 8),
                const Text(
                  'Este Pix expirou',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Por segurança, o código tem prazo de validade. Gere um novo Pix para pagar este pedido.',
              style: TextStyle(color: c.muted, fontSize: 13, height: 1.35),
            ),
            const SizedBox(height: 12),
            BusyButton(
              label: 'Gerar novo Pix',
              icon: Icons.refresh_rounded,
              busy: _busy,
              onPressed: _renew,
            ),
          ],
        ),
      );
    }
    return Container(
      margin: const EdgeInsets.only(top: 14),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: c.pinkTint,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: c.pink.withValues(alpha: 0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Pague com Pix',
                  style: TextStyle(
                    color: c.pinkStrong,
                    fontWeight: FontWeight.w800,
                    fontSize: 16,
                  ),
                ),
              ),
              Semantics(
                label: 'O código vale por mais ${pixLeft(_left)}',
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 5,
                  ),
                  decoration: BoxDecoration(
                    color: c.surface,
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.schedule_rounded,
                        size: 15,
                        color: _left < 300 ? c.bad : c.ink,
                      ),
                      const SizedBox(width: 5),
                      Text(
                        'Expira em ${pixLeft(_left)}',
                        style: TextStyle(
                          color: _left < 300 ? c.bad : c.ink,
                          fontWeight: FontWeight.w800,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 2),
          Text.rich(
            TextSpan(
              children: [
                const TextSpan(text: 'Valor: '),
                TextSpan(
                  text: money(o.total),
                  style: TextStyle(
                    color: c.ink,
                    fontWeight: FontWeight.w800,
                    fontSize: 15,
                  ),
                ),
              ],
            ),
            style: TextStyle(color: c.muted, fontSize: 13),
          ),
          Text(
            'Assim que o pagamento for confirmado, o pedido é marcado como pago automaticamente.',
            style: TextStyle(color: c.muted, fontSize: 13, height: 1.35),
          ),
          if (o.pixQrBase64.isNotEmpty)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 14),
                child: Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Image.memory(
                    base64Decode(o.pixQrBase64),
                    width: 164,
                    height: 164,
                  ),
                ),
              ),
            )
          else
            const SizedBox(height: 12),
          Text(
            'Pix copia e cola',
            style: TextStyle(
              color: c.muted,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: c.surface,
              borderRadius: BorderRadius.circular(12),
            ),
            child: SelectableText(
              o.pixCode,
              maxLines: 3,
              style: TextStyle(
                color: c.muted,
                fontSize: 12,
                height: 1.4,
                fontFamily: 'monospace',
              ),
            ),
          ),
          const SizedBox(height: 12),
          BusyButton(
            label: 'Copiar Pix copia e cola',
            icon: Icons.copy_rounded,
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: o.pixCode));
              if (context.mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('Pix copiado!')));
              }
            },
          ),
        ],
      ),
    );
  }
}

/// Cartão em análise no Mercado Pago: sem formulário (outra tentativa seria barrada e poderia cobrar duas vezes).
class _CardInReviewBox extends StatelessWidget {
  const _CardInReviewBox();

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: AppCard(
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(Icons.hourglass_top_rounded, color: c.muted),
          const SizedBox(width: 10),
          Expanded(child: Text(cardInReview, style: TextStyle(color: c.muted, fontSize: 13.5))),
        ]),
      ),
    );
  }
}

/// Pagamento com cartão recusado: deixa tentar outro cartão no mesmo pedido.
class _CardRetryBox extends StatefulWidget {
  const _CardRetryBox(this.o, {required this.onChanged});
  final ShopOrder o;
  final Future<void> Function() onChanged;
  @override
  State<_CardRetryBox> createState() => _CardRetryBoxState();
}

class _CardRetryBoxState extends State<_CardRetryBox> {
  final _cardKey = GlobalKey<CardFieldsState>();
  bool _busy = false;
  bool _cardEnabled = false;
  String? _publicKey;
  List<CardBrand> _brands = const [];

  @override
  void initState() {
    super.initState();
    context.read<Session>().customerApi.get('/api/payment-config').then((r) {
      final d = Map<String, dynamic>.from(r as Map);
      if (mounted) setState(() { _cardEnabled = d['cardEnabled'] == true; _publicKey = d['publicKey'] as String?; _brands = parseBrands(d['brands']); });
    }).catchError((_) {});
  }

  Future<void> _retry() async {
    final api = context.read<Session>().customerApi;
    final t = await _cardKey.currentState?.tokenize();
    if (t == null || !mounted) return;
    setState(() => _busy = true);
    try {
      final path = '/api/cliente/pedidos/${widget.o.id}/cartao';
      var r = Map<String, dynamic>.from(await api.post(path, {
        'card_token': t.cardToken, 'card_payment_method_id': t.paymentMethodId, 'cpf': t.cpf, 'installments': t.installments,
        if (t.issuerId.isNotEmpty) 'card_issuer_id': t.issuerId,
        if (t.typeId.isNotEmpty) 'card_payment_type_id': t.typeId,
        if (t.deviceId.isNotEmpty) 'device_id': t.deviceId,
      }) as Map);
      // o banco pode pedir a confirmação do cartão (3DS): mostra aqui mesmo e confere o resultado sem cobrar de novo
      if (mounted) r = await withChallenge(context, r, () => api.get(path));
      if (r['approved'] == true) {
        Toast.ok('Pagamento aprovado!');
      } else if (r['pending'] == true) {
        Toast.warn(cardInReview);
      } else {
        Toast.error('${r['reason'] ?? 'Pagamento recusado'}');
      }
      await widget.onChanged();
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_cardEnabled || _publicKey == null) return const SizedBox.shrink();
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: AppCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Pagamento com cartão ainda não aprovado. Tente outro cartão:', style: TextStyle(color: c.muted, fontSize: 13.5)),
          const SizedBox(height: 12),
          CardFields(key: _cardKey, publicKey: _publicKey!, brands: _brands, amount: widget.o.total),
          const SizedBox(height: 14),
          BusyButton(label: 'Tentar pagar', busy: _busy, onPressed: _retry),
        ]),
      ),
    );
  }
}

/// "Cancelar pedido": pede o motivo e cancela. Só aparece enquanto a produção não começou; se o pedido já foi pago, o Pix volta na hora.
class _CancelButton extends StatelessWidget {
  const _CancelButton(this.o, {required this.onChanged});
  final ShopOrder o;
  final Future<void> Function() onChanged;

  Future<void> _cancel(BuildContext context) async {
    final api = context.read<Session>().customerApi;
    final r = await showDialog<({String code, String note})>(
      context: context,
      builder: (_) => _CancelDialog(o),
    );
    if (r == null) return;
    try {
      final res = await api.post('/api/cliente/pedidos/${o.id}/cancelar', {
        'reason_code': r.code,
        'reason': r.note,
      });
      final back = res is Map ? (res['refunded'] as num?)?.toDouble() ?? 0 : 0;
      Toast.ok(back > 0 ? 'Pedido cancelado. ${money(back)} voltam ${paidByCard(o) ? 'como estorno no cartão' : 'por Pix'}.' : 'Pedido cancelado');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      await onChanged(); // mostra a situação de verdade (cancelado, ou já pago/em produção)
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        onPressed: () => _cancel(context),
        icon: const Icon(Icons.block_rounded, size: 18),
        label: Text(o.paid ? (paidByCard(o) ? 'Cancelar e receber o estorno no cartão' : 'Cancelar e receber o Pix de volta') : 'Cancelar pedido'),
        style: OutlinedButton.styleFrom(foregroundColor: c.bad),
      ),
    );
  }
}

class _CancelDialog extends StatefulWidget {
  const _CancelDialog(this.o);
  final ShopOrder o;
  @override
  State<_CancelDialog> createState() => _CancelDialogState();
}

class _CancelDialogState extends State<_CancelDialog> {
  final _note = TextEditingController();
  String? _code;

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final o = widget.o;
    final needNote = _code == 'outro';
    final ok = _code != null && (!needNote || _note.text.trim().length >= 3);
    return AlertDialog(
      title: Text('Cancelar o pedido #${o.code}?'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              o.paid
                  ? paidByCard(o)
                      ? 'Ainda não começamos a produzir. Estornamos ${money(o.total)} no seu cartão; aparece na fatura em até 2 ciclos, conforme o banco.'
                      : 'Ainda não começamos a produzir. Você recebe ${money(o.total)} de volta por Pix, na conta que pagou, normalmente em poucos minutos.'
                  : 'Ele ainda não entrou em produção. Se houver um Pix pendente, o código será desativado.',
              style: TextStyle(color: c.muted, height: 1.4),
            ),
            const SizedBox(height: 12),
            const Text('Por que você está cancelando?', style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            RadioGroup<String>(
              groupValue: _code,
              onChanged: (v) => setState(() => _code = v),
              child: Column(children: [
                for (final r in cancelReasons)
                  RadioListTile<String>(dense: true, contentPadding: EdgeInsets.zero, value: r.id, title: Text(r.label, style: const TextStyle(fontSize: 14))),
              ]),
            ),
            if (needNote) AppTextField(label: 'Conte o motivo', controller: _note, maxLength: 150, onChanged: (_) => setState(() {})),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Voltar')),
        FilledButton(
          onPressed: ok ? () => Navigator.of(context).pop((code: _code!, note: _note.text.trim())) : null,
          style: FilledButton.styleFrom(backgroundColor: c.bad, foregroundColor: Colors.white),
          child: const Text('Cancelar pedido'),
        ),
      ],
    );
  }
}

String _payState(ShopOrder o) => o.cardInReview ? 'Em análise' : switch (o.paymentState) {
      'pago' => 'Pago',
      'devolucao_parcial' => 'Devolvido ${money(o.refundedTotal)}',
      'devolvido' => 'Devolvido',
      'cancelado' => 'Cancelado',
      'expirado' => 'Pix vencido',
      '' => o.paid ? 'Pago' : 'Aguardando pagamento',
      _ => 'Aguardando pagamento',
    };

String _when(String iso) {
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return '';
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(d.day)}/${two(d.month)} às ${two(d.hour)}:${two(d.minute)}';
}

/// Selo do topo do card quando há cancelamento ou devolução (ganha do status do pedido).
(String, String)? _refundBadge(ShopOrder o) {
  final r = o.refunds.isEmpty ? null : o.refunds.first;
  if (r != null && r.requested) {
    switch (r.status) {
      case 'solicitado':
        return ('Devolução em análise', 'warn');
      case 'negado':
        return ('Devolução negada', 'bad');
      case 'concluido':
        return ('Devolvido ${money(r.approvedAmount ?? 0)}', 'ok');
      default:
        return ('Devolução aprovada', 'ok');
    }
  }
  if (o.cancel?.fullRefund ?? false) return ('Devolvido ${money(o.refundedTotal)}', 'ok');
  if (o.cancel != null || o.status == 'cancelado') return ('Cancelado', 'neutral');
  if (o.refundedTotal > 0) return ('Devolvido ${money(o.refundedTotal)}', 'ok');
  return null;
}

/// Quem cancelou, o motivo e a mensagem da loja.
class _CancelInfo extends StatelessWidget {
  const _CancelInfo(this.cancel);
  final OrderCancel cancel;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final byStore = cancel.by != 'cliente' && !cancel.fullRefund;
    return Semantics(
      container: true,
      label: 'Cancelamento',
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 14),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: byStore ? c.bad.withValues(alpha: 0.07) : c.surface2,
          borderRadius: BorderRadius.circular(18),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(
            cancel.fullRefund ? 'PEDIDO ENCERRADO' : cancel.by == 'cliente' ? 'VOCÊ CANCELOU' : cancel.by == 'sistema' ? 'CANCELADO AUTOMATICAMENTE' : 'CANCELADO PELA LOJA',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 0.4, color: byStore ? c.bad : c.muted),
          ),
          const SizedBox(height: 4),
          if (cancel.fullRefund) ...[
            Text(cancel.reason, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('Como o valor voltou para você, a loja não vai mais produzir este pedido.', style: TextStyle(color: c.muted)),
            ),
          ] else if (cancel.reason.isNotEmpty)
            Text('Motivo: ${cancel.reason}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
          if (cancel.note.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text.rich(TextSpan(children: [
                TextSpan(text: byStore ? 'Mensagem da loja: ' : 'Você escreveu: ', style: TextStyle(color: c.muted)),
                TextSpan(text: cancel.note),
              ])),
            ),
        ]),
      ),
    );
  }
}

/// Andamento de uma devolução em passos (bolinhas), como no design.
class _Steps extends StatelessWidget {
  const _Steps(this.labels, this.done, {this.finalOk = false});
  final List<String> labels;
  final int done; // quantos passos já aconteceram
  final bool finalOk;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Semantics(
      label: 'Andamento da devolução: ${labels[(done - 1).clamp(0, labels.length - 1)]}',
      child: Row(children: [
        for (var i = 0; i < labels.length; i++)
          Expanded(
            child: Column(children: [
              Container(
                width: 22,
                height: 22,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: i < done ? (finalOk && i == labels.length - 1 ? c.ok : c.pink) : Colors.white,
                  border: Border.all(color: i < done ? Colors.transparent : (i == done ? c.pink : c.line), width: 2),
                ),
              ),
              const SizedBox(height: 4),
              ExcludeSemantics(
                child: Text(labels[i],
                    textAlign: TextAlign.center,
                    style: TextStyle(fontSize: 11.5, fontWeight: i <= done ? FontWeight.w700 : FontWeight.w400, color: i < done || i == done ? c.ink : c.muted)),
              ),
            ]),
          ),
      ]),
    );
  }
}

/// Devoluções do pedido (andamento, em palavras simples) e o botão "Pedir devolução" quando o servidor permite.
class _RefundBox extends StatelessWidget {
  const _RefundBox(this.o, {required this.onChanged});
  final ShopOrder o;
  final Future<void> Function() onChanged;

  Future<void> _withdraw(BuildContext context) async {
    try {
      await context.read<Session>().customerApi.delete('/api/cliente/pedidos/${o.id}/devolucao');
      Toast.ok('Solicitação cancelada');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      await onChanged();
    }
  }

  Widget _one(BuildContext context, CustomerRefund r) {
    final c = context.c;
    final denied = r.status == 'negado';
    final done = r.status == 'concluido';
    final amount = r.approvedAmount ?? r.requestedAmount;
    final card = paidByCard(o);
    final backDone = card ? 'Estornado no cartão' : 'Pix devolvido';
    final List<Widget> lines;
    if (r.requested) {
      final partial = r.approvedAmount != null && r.approvedAmount! < r.requestedAmount;
      lines = [
        Text(
          denied
              ? 'Motivo da loja: ${r.decisionReason.isEmpty ? 'devolução negada' : r.decisionReason}'
              : r.status == 'solicitado'
                  ? 'Você pediu ${money(r.requestedAmount)} de volta'
                  : partial
                      ? 'Devolução aprovada em parte: ${money(r.approvedAmount!)} de ${money(r.requestedAmount)}'
                      : 'Devolução aprovada: ${money(amount)}',
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        if (r.status == 'solicitado' && r.reason.isNotEmpty)
          Text('Motivo: ${r.reason}${r.photos > 0 ? ' · ${r.photos} foto${r.photos > 1 ? 's' : ''}' : ''}', style: TextStyle(color: c.muted, fontSize: 13.5)),
        if (r.decisionNote.isNotEmpty)
          Text.rich(TextSpan(children: [TextSpan(text: 'Mensagem da loja: ', style: TextStyle(color: c.muted)), TextSpan(text: r.decisionNote)]), style: const TextStyle(fontSize: 13.5)),
        if (!denied) ...[
          const SizedBox(height: 8),
          _Steps(['Solicitada', 'Em análise', 'Aprovada', backDone], r.status == 'solicitado' ? 1 : done ? 4 : 3, finalOk: done),
          if (card && done) Text('O estorno aparece na fatura do cartão em até 2 ciclos, conforme o banco.', style: TextStyle(color: c.muted, fontSize: 13)),
        ],
        if (r.status == 'solicitado')
          Text('Respondemos em até 1 dia útil. Você recebe uma notificação.', style: TextStyle(color: c.muted, fontSize: 13)),
      ];
    } else {
      // devolução da loja, do cancelamento antes da produção ou automática: sem análise
      final title = done ? '$backDone: ${money(amount)}' : 'Devolução de ${money(amount)} em processamento';
      lines = [
        if (r.kind == 'loja' && o.cancel == null)
          Text('DEVOLUÇÃO DA LOJA', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 0.4, color: c.pinkStrong)),
        Text(title, style: const TextStyle(fontWeight: FontWeight.w800)),
        if (r.kind == 'loja' && o.cancel == null && r.decisionNote.isNotEmpty)
          Text.rich(TextSpan(children: [TextSpan(text: 'Mensagem da loja: ', style: TextStyle(color: c.muted)), TextSpan(text: r.decisionNote)]), style: const TextStyle(fontSize: 13.5)),
        const SizedBox(height: 8),
        _Steps(['Devolução iniciada', 'Em processamento', backDone], done ? 3 : 2, finalOk: done),
        if (card && done) Text('O estorno aparece na fatura do cartão em até 2 ciclos, conforme o banco.', style: TextStyle(color: c.muted, fontSize: 13)),
      ];
    }
    return Semantics(
      container: true,
      label: 'Devolução',
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 12),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: denied ? c.bad.withValues(alpha: 0.06) : c.pinkTint.withValues(alpha: 0.6), borderRadius: BorderRadius.circular(18)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, spacing: 4, children: [
          ...lines,
          if (done && r.refundedAt != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text('O valor voltou para a conta que pagou, em ${_when(r.refundedAt!)}.', style: TextStyle(color: c.ok, fontWeight: FontWeight.w600, fontSize: 13)),
            ),
          if (r.requested && r.status == 'solicitado')
            TextButton(
              onPressed: () => _withdraw(context),
              style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(0, 44), foregroundColor: c.pinkStrong),
              child: const Text('Desistir da solicitação', style: TextStyle(decoration: TextDecoration.underline)),
            ),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final solicitar = o.refundMode == 'solicitacao';
    if (o.refunds.isEmpty && !solicitar) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final r in o.refunds) _one(context, r),
      if (solicitar) ...[
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: OutlinedButton(
            onPressed: () async {
              await context.push('/cliente/pedidos/${o.id}/devolucao');
              await onChanged();
            },
            child: const Text('Pedir devolução'),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(
            '${o.refundDeadline == null ? '' : 'Você pode pedir até ${_when(o.refundDeadline!)}. '}A devolução depende de análise e não é garantida.',
            style: TextStyle(color: c.muted, fontSize: 12),
          ),
        ),
      ],
    ]);
  }
}

/// "Quer saber quando o pedido mudar?": só aparece quando o build tem avisos e a cliente ainda não autorizou.
class _PushOffer extends StatelessWidget {
  const _PushOffer();
  @override
  Widget build(BuildContext context) {
    final push = context.watch<CustomerPush>();
    if (!push.shouldOffer) return const SizedBox.shrink();
    final c = context.c;
    return Container(
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(22), border: Border.all(color: c.pink, width: 2)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(14)),
            child: Icon(Icons.notifications_none_rounded, color: c.pinkStrong),
          ),
          const SizedBox(width: 12),
          const Expanded(child: Text('Quer saber quando o pedido mudar?', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
        ]),
        const SizedBox(height: 8),
        Text('Avisamos quando o Pix for confirmado, quando o pedido ficar pronto e sobre cancelamentos e devoluções. Nada de propaganda.',
            style: TextStyle(color: c.muted, height: 1.4)),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: BusyButton(label: 'Ativar notificações', onPressed: push.enable)),
          const SizedBox(width: 10),
          OutlinedButton(onPressed: push.dismiss, style: OutlinedButton.styleFrom(minimumSize: const Size(0, 48)), child: const Text('Agora não')),
        ]),
      ]),
    );
  }
}
