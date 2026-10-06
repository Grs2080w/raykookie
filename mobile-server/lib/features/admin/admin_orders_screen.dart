import 'package:flutter/material.dart';
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

import '../../core/push.dart';

import 'pay_link_sheet.dart';
import 'shop_order_actions.dart';

class _OrdersData {
  _OrdersData(this.orders, this.recipes);
  final List<OrderModel> orders;
  final List<RecipeRow> recipes;
}

class AdminOrdersScreen extends StatelessWidget {
  const AdminOrdersScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Loader<_OrdersData>(
      key: ValueKey(context.watch<PushService>().received), // chegou aviso com o app aberto: recarrega
      load: () async {
        final r = await Future.wait([
          api.get('/api/orders'),
          api.get('/api/recipes'),
        ]);
        return _OrdersData(
          [for (final e in r[0] as List) OrderModel.fromJson(e)],
          [for (final e in r[1] as List) RecipeRow.fromJson(e)],
        );
      },
      builder: (context, d, reload) => _OrdersView(data: d, reload: reload),
    );
  }
}

class _OrdersView extends StatefulWidget {
  const _OrdersView({required this.data, required this.reload});
  final _OrdersData data;
  final Future<void> Function() reload;
  @override
  State<_OrdersView> createState() => _OrdersViewState();
}

class _OrdersViewState extends State<_OrdersView> {
  String _filter = 'all', _search = '';

  ApiClient get _api => context.read<Session>().ownerApi;

  Future<void> _edit([OrderModel? o]) async {
    final saved = await showFormSheet<bool>(
      context,
      title: o == null ? 'Novo pedido' : 'Editar pedido',
      child: OrderFormSheet(existing: o, recipes: widget.data.recipes),
    );
    if (saved == true) await widget.reload();
  }

  Future<void> _quick(OrderModel o, Map<String, String> patch) async {
    try {
      await _api.patch('/api/orders/${o.id}', patch);
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  Future<void> _delete(OrderModel o) async {
    if (!await confirmDialog(
      context,
      title: 'Excluir o pedido ${o.number}?',
      message: 'Ele deixa de contar nas metas do mês. Para manter o histórico, prefira marcar como Cancelado.',
    ))
      return;
    try {
      await _api.delete('/api/orders/${o.id}');
      Toast.ok('Pedido excluído');
      await widget.reload();
    } on ApiException catch (e) {
      Toast.error(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final orders = widget.data.orders;
    final counts = {
      for (final s in orderStatus)
        s.$1: orders.where((o) => o.status == s.$1).length,
    };
    final rows = orders
        .where(
          (o) =>
              (_filter == 'all' || o.status == _filter) &&
              (_search.isEmpty ||
                  '${o.customer} ${o.product} ${o.number}'
                      .toLowerCase()
                      .contains(_search.toLowerCase())),
        )
        .toList();
    final open = orders
        .where((o) => !['entregue', 'cancelado'].contains(o.status))
        .length;
    // devoluções aguardando (um pedido do app tem uma linha por item: conta o pedido uma vez)
    final waiting = {for (final o in orders) if (o.refundWaiting) o.shopOrderId}.length;
    final receivable = orders
        .where((o) => o.status != 'cancelado' && o.payment != 'pago')
        .fold<double>(0, (s, o) => s + o.total);

    Widget stat(String label, String value) => Expanded(
      child: AppCard(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: TextStyle(color: c.muted, fontSize: 12.5)),
            FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(value, style: display(context, size: 24)),
            ),
          ],
        ),
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 12),
        const PageTitle(
          'Pedidos',
          subtitle: 'Acompanhe cada encomenda, da entrada até a entrega e o pagamento.',
        ),
        if (waiting > 0)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Material(
              color: c.warn.withValues(alpha: 0.14),
              borderRadius: BorderRadius.circular(18),
              child: InkWell(
                borderRadius: BorderRadius.circular(18),
                onTap: () async {
                  await context.push('/painel/devolucoes');
                  await widget.reload();
                },
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Row(children: [
                    Icon(Icons.undo_rounded, color: c.warn),
                    const SizedBox(width: 12),
                    Expanded(child: Text(waiting == 1 ? '1 devolução aguardando sua decisão' : '$waiting devoluções aguardando sua decisão', style: const TextStyle(fontWeight: FontWeight.w700))),
                    Text('Ver', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w800)),
                  ]),
                ),
              ),
            ),
          ),
        Row(
          children: [
            stat('Em andamento', '$open'),
            const SizedBox(width: 8),
            stat('A receber', money(receivable)),
            const SizedBox(width: 8),
            stat('Total', '${orders.length}'),
          ],
        ),
        const SizedBox(height: 14),
        BusyButton(
          label: 'Novo pedido',
          icon: Icons.add_rounded,
          onPressed: () => _edit(),
        ),
        const SizedBox(height: 12),
        TextField(
          decoration: const InputDecoration(
            hintText: 'Buscar por cliente, produto ou número',
            prefixIcon: Icon(Icons.search_rounded),
          ),
          onChanged: (v) => setState(() => _search = v),
        ),
        const SizedBox(height: 10),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (final (id, label, n) in [
                ('all', 'Todos', orders.length),
                for (final s in orderStatus) (s.$1, s.$2, counts[s.$1] ?? 0),
              ]) ...[
                ChoiceChip(
                  label: Text('$label $n'),
                  selected: _filter == id,
                  onSelected: (_) => setState(() => _filter = id),
                ),
                const SizedBox(width: 8),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        if (orders.isEmpty)
          EmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'Nenhum pedido registrado',
            text: 'Registre a primeira encomenda. Se escolher uma receita, o preço já vem preenchido.',
            action: BusyButton(
              label: 'Novo pedido',
              expand: false,
              icon: Icons.add_rounded,
              onPressed: () => _edit(),
            ),
          )
        else if (rows.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(28),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: c.line),
            ),
            child: Text(
              'Nenhum pedido encontrado.',
              textAlign: TextAlign.center,
              style: TextStyle(color: c.muted),
            ),
          )
        else
          for (final o in rows) _orderCard(context, o),
      ],
    );
  }

  Future<void> _shopAction(OrderModel o, {required bool cancel}) async {
    final done = await showFormSheet<bool>(
      context,
      title: cancel ? 'Cancelar pedido #${o.shopCode}' : 'Devolver valor · pedido #${o.shopCode}',
      child: ShopOrderActionSheet(order: o, cancel: cancel),
    );
    if (done == true) await widget.reload();
  }

  Future<void> _payLink(OrderModel o) async {
    await showFormSheet<bool>(context, title: 'Link de pagamento · pedido #${o.number}', child: PayLinkSheet(order: o));
  }

  Future<void> _openRefund(int id) async {
    await context.push('/painel/devolucoes/$id');
    await widget.reload();
  }

  /// Pedido do app: faixa com a devolução mais recente (qualquer estado; toque abre o detalhe), faixa de cancelado/encerrado
  /// e os botões de cancelar/devolver, só quando ainda cabem (nada com solicitação aguardando ou com tudo já devolvido).
  List<Widget> _shopBands(BuildContext context, OrderModel o) {
    final c = context.c;
    final r = o.shopRefund;
    final cancel = o.shopCancel;
    final left = o.shopTotal - o.shopRefunded;
    final rs = r?['status'] as String?;
    double amt(String k) => (r?[k] as num?)?.toDouble() ?? 0;
    String reason(String? code) => code == null || code.isEmpty ? '' : reasonLabel(code);
    final partial = o.shopRefunded > 0 && left > 0.004;

    // (título, linha, ação, cor)
    final (String, String, String?, Color)? band = switch (rs) {
      'solicitado' => (
          'DEVOLUÇÃO AGUARDANDO',
          ['Pede ${money(amt('requested_amount'))}', reason(r?['reason_code'] as String?), if ((r?['photos'] as num? ?? 0) > 0) '${r?['photos']} foto(s)'].where((s) => s.isNotEmpty).join(' · '),
          'Ver e decidir',
          c.warn,
        ),
      'aprovado' || 'processando' => ('DEVOLUÇÃO EM PROCESSAMENTO', '${money(amt('approved_amount'))} por Pix', null, c.pinkStrong),
      'falhou' => (
          'DEVOLUÇÃO PARADA',
          '${money(amt('approved_amount'))} · ${switch (r?['fail_reason']) { 'saldo' => 'falta saldo no Mercado Pago', 'prazo' => 'o prazo do Pix acabou', _ => 'o Mercado Pago não respondeu' }}',
          'Ver e tentar de novo',
          c.bad,
        ),
      'concluido' => (
          partial ? 'DEVOLUÇÃO CONCLUÍDA (PARCIAL)' : 'DEVOLUÇÃO CONCLUÍDA',
          partial
              ? '${money(o.shopRefunded)} de ${money(o.shopTotal)} devolvidos'
              : ['${money(o.shopRefunded)} devolvidos', if (r?['kind'] == 'solicitado') reason(r?['reason_code'] as String?)].where((s) => s.isNotEmpty).join(' · '),
          r?['kind'] == 'solicitado' ? 'Ver detalhes' : null,
          c.ok,
        ),
      'negado' => ('DEVOLUÇÃO NEGADA', ['Pedia ${money(amt('requested_amount'))}', reason(r?['decision_code'] as String?)].where((s) => s.isNotEmpty).join(' · '), 'Ver detalhes', c.muted),
      _ => null,
    };
    // devolução feita junto com o cancelamento: a faixa de cancelado já diz quanto voltou (a não ser que tenha travado)
    final showBand = band != null && !(cancel != null && r?['kind'] != 'solicitado' && rs == 'concluido');
    final closed = cancel != null || o.status == 'cancelado';
    final canAct = !closed && rs != 'solicitado' && !(o.shopPaid && left <= 0.004);

    return [
      if (showBand)
        Padding(
          padding: const EdgeInsets.only(top: 10, right: 8),
          child: Material(
            color: band.$4.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(16),
            child: InkWell(
              borderRadius: BorderRadius.circular(16),
              onTap: band.$3 == null ? null : () => _openRefund(r!['id'] as int),
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: SizedBox(
                  width: double.infinity,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(band.$1, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 0.4, color: band.$4)),
                    Text(band.$2, style: const TextStyle(fontWeight: FontWeight.w700)),
                    if (band.$3 != null) Text(band.$3!, style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700)),
                  ]),
                ),
              ),
            ),
          ),
        ),
      if (cancel != null)
        Container(
          width: double.infinity,
          margin: const EdgeInsets.only(top: 10, right: 8),
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(16)),
          child: Text.rich(
            cancel['code'] == fullRefundCode
                ? const TextSpan(children: [
                    TextSpan(text: 'Pedido encerrado: ', style: TextStyle(fontWeight: FontWeight.w800)),
                    TextSpan(text: 'todo o valor foi devolvido. A produção parou.'),
                  ])
                : TextSpan(children: [
                    TextSpan(text: cancel['by'] == 'cliente' ? 'Cancelado pela cliente: ' : cancel['by'] == 'sistema' ? 'Cancelado automaticamente: ' : 'Cancelado por você: ', style: const TextStyle(fontWeight: FontWeight.w800)),
                    TextSpan(text: '${cancel['reason'] ?? ''}${(cancel['note'] ?? '').toString().isEmpty ? '' : ' (${cancel['note']})'}'),
                    if (o.shopRefunded > 0) TextSpan(text: ' ${money(o.shopRefunded)} devolvidos.', style: TextStyle(color: c.ok, fontWeight: FontWeight.w700)),
                  ]),
            style: const TextStyle(fontSize: 14, height: 1.4),
          ),
        ),
      if (canAct)
        Padding(
          padding: const EdgeInsets.only(top: 10, right: 8),
          child: Row(children: [
            if (o.status != 'entregue')
              Expanded(
                child: OutlinedButton(
                  onPressed: () => _shopAction(o, cancel: true),
                  style: OutlinedButton.styleFrom(foregroundColor: c.bad, minimumSize: const Size(0, 46)),
                  child: Text(
                    !o.shopPaid ? 'Cancelar pedido' : o.shopRefunded > 0 ? 'Cancelar e devolver ${money(left)}' : 'Cancelar e devolver o valor pago',
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            if (o.status != 'entregue' && o.shopPaid) const SizedBox(width: 8),
            if (o.shopPaid)
              Expanded(
                child: OutlinedButton(
                  onPressed: () => _shopAction(o, cancel: false),
                  style: OutlinedButton.styleFrom(minimumSize: const Size(0, 46)),
                  child: const Text('Devolver valor'),
                ),
              ),
          ]),
        ),
    ];
  }

  Widget _orderCard(BuildContext context, OrderModel o) {
    final c = context.c;
    final st = orderStatus.where((s) => s.$1 == o.status).firstOrNull;
    final pay = orderPayment.where((s) => s.$1 == o.payment).firstOrNull;
    final shop = o.shopOrderId != null;
    final shopClosed = shop && o.shopCancel != null;
    Widget menu(
      String label,
      List<(String, String, String)> options,
      String current,
      String field,
      (String, String, String)? shown,
    ) => PopupMenuButton<String>(
      tooltip: label,
      enabled: !shopClosed, // pedido do app encerrado: o cliente já viu o cancelamento e o dinheiro já voltou

      // pedido do app: cancelar abre a folha que pede o motivo (a cliente vê) e devolve o Pix se já foi pago
      onSelected: (v) => field == 'status' && v == 'cancelado' && o.shopOrderId != null ? _shopAction(o, cancel: true) : _quick(o, {field: v}),
      itemBuilder: (_) => [
        for (final s in options)
          PopupMenuItem(
            value: s.$1,
            child: Text(
              s.$2,
              style: TextStyle(
                fontWeight: s.$1 == current ? FontWeight.w800 : FontWeight.w500,
              ),
            ),
          ),
      ],
      child: AppBadge(shown?.$2 ?? current, tone: shown?.$3 ?? 'neutral'),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: AppCard(
        // pedido pago e ainda em andamento ganha a borda verde
        borderColor:
            o.payment == 'pago' &&
                o.status != 'entregue' &&
                o.status != 'cancelado'
            ? c.ok
            : null,
        padding: const EdgeInsets.fromLTRB(14, 12, 6, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  o.number.toString().padLeft(2, '0'),
                  style: display(context, size: 22, color: c.pinkStrong),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        o.customer,
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          fontSize: 16,
                        ),
                      ),
                      Text(
                        '${number(o.qty)}× ${o.product}',
                        style: TextStyle(color: c.muted, fontSize: 13.5),
                      ),
                      if (o.shopOrderId != null && o.shopPaymentMethod != null)
                        Text(
                          paymentLabel(o.shopPaymentMethod!) + (o.shopPaymentMethod == 'cartao' && o.shopInstallments > 1 ? ' · ${o.shopInstallments}x' : ''),
                          style: TextStyle(color: c.muted, fontSize: 12.5),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: Text(
                    money(o.total),
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'Entrega: ${dateBR(o.deliveryDate)}',
              style: TextStyle(color: c.muted, fontSize: 13),
            ),
            if (o.address.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4, right: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.place_outlined, size: 16, color: c.pinkStrong),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text.rich(
                        TextSpan(
                          children: [
                            const TextSpan(
                              text: 'Endereço: ',
                              style: TextStyle(fontWeight: FontWeight.w700),
                            ),
                            TextSpan(text: o.address),
                          ],
                        ),
                        style: TextStyle(color: c.ink, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            if (o.notes.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4, right: 8),
                child: Text(
                  o.notes,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: c.muted, fontSize: 12.5),
                ),
              ),
            if (o.shopOrderId != null) ..._shopBands(context, o),
            if (o.canPayLink)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: () => _payLink(o),
                    style: OutlinedButton.styleFrom(minimumSize: const Size(0, 46)),
                    icon: const Icon(Icons.ios_share_rounded, size: 18),
                    label: const Text('Compartilhar link de pagamento'),
                  ),
                ),
              ),
            const SizedBox(height: 8),
            Row(
              children: [
                menu(
                  'Mudar status',
                  [for (final s in orderStatus) s],
                  o.status,
                  'status',
                  st,
                ),
                const SizedBox(width: 8),
                menu(
                  'Mudar pagamento',
                  [for (final s in orderPayment) s],
                  o.payment,
                  'payment',
                  pay,
                ),
                const Spacer(),
                // pedido do app: não some da gestão (a cliente continua vendo) e, encerrado, não muda mais
                if (!shopClosed)
                  IconButton(
                    onPressed: () => _edit(o),
                    icon: const Icon(Icons.edit_outlined),
                    tooltip: 'Editar pedido ${o.number}',
                  ),
                if (!shop)
                  IconButton(
                    onPressed: () => _delete(o),
                    icon: Icon(Icons.delete_outline_rounded, color: c.bad),
                    tooltip: 'Excluir pedido ${o.number}',
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Formulário de pedido (novo ou edição).
class OrderFormSheet extends StatefulWidget {
  const OrderFormSheet({super.key, this.existing, required this.recipes});
  final OrderModel? existing;
  final List<RecipeRow> recipes;
  @override
  State<OrderFormSheet> createState() => _OrderFormSheetState();
}

class _OrderFormSheetState extends State<OrderFormSheet> {
  late final _customer = TextEditingController(
    text: widget.existing?.customer ?? '',
  );
  late final _product = TextEditingController(
    text: widget.existing?.product ?? '',
  );
  late final _qty = TextEditingController(
    text: widget.existing == null ? '1' : fieldNum(widget.existing!.qty),
  );
  late final _price = TextEditingController(
    text: widget.existing == null ? '' : fieldNum(widget.existing!.unitPrice),
  );
  late final _notes = TextEditingController(text: widget.existing?.notes ?? '');
  late int? _recipe = widget.existing?.recipeId;
  late String _orderDate = widget.existing?.orderDate ?? todayIso();
  late String _delivery = widget.existing?.deliveryDate ?? '';
  late String _status = widget.existing?.status ?? 'novo';
  late String _payment = widget.existing?.payment ?? 'pendente';
  late final bool _shop = widget.existing?.shopOrderId != null;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_customer, _product, _qty, _price, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  void _pickRecipe(int? id) {
    setState(() {
      _recipe = id;
      final r = widget.recipes.where((x) => x.id == id).firstOrNull;
      if (r != null) {
        _product.text = r.name;
        _price.text = fieldNum(r.pricing?.rounded ?? 0);
      }
    });
  }

  Future<String?> _pickDate(String current, {bool allowEmpty = false}) async {
    final now = DateTime.now();
    final initial = current.isEmpty ? now : DateTime.tryParse(current) ?? now;
    final d = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year - 1),
      lastDate: DateTime(now.year + 3),
      cancelText: allowEmpty ? 'Limpar' : 'Cancelar',
      confirmText: 'Escolher',
    );
    return d == null ? (allowEmpty ? '' : null) : todayIso(d);
  }

  Future<void> _save() async {
    if (_customer.text.trim().isEmpty)
      return Toast.error('Informe o nome do cliente.');
    if (_product.text.trim().isEmpty) return Toast.error('Informe o produto.');
    if (parseNum(_qty.text) <= 0)
      return Toast.error('A quantidade precisa ser maior que zero.');
    final body = {
      'order_date': _orderDate,
      'customer': _customer.text,
      'recipe_id': _recipe?.toString() ?? '',
      'product': _product.text,
      'qty': _qty.text,
      'unit_price': _price.text,
      'delivery_date': _delivery,
      'status': _status,
      'payment': _payment,
      'notes': _notes.text,
    };
    final api = context.read<Session>().ownerApi;
    setState(() => _busy = true);
    try {
      if (widget.existing == null) {
        await api.post('/api/orders', body);
      } else {
        await api.patch('/api/orders/${widget.existing!.id}', body);
      }
      Toast.ok(
        widget.existing == null ? 'Pedido registrado' : 'Pedido atualizado',
      );
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _dateField(String label, String value, VoidCallback onTap) =>
      LabeledField(
        label: label,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(14),
          child: InputDecorator(
            decoration: const InputDecoration(
              suffixIcon: Icon(Icons.calendar_month_outlined),
            ),
            child: Text(
              value.isEmpty ? '—' : dateBR(value),
              style: const TextStyle(fontSize: 16),
            ),
          ),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final total = parseNum(_qty.text) * parseNum(_price.text);
    return Column(
      children: [
        AppTextField(
          label: 'Cliente',
          controller: _customer,
          maxLength: 80,
          capitalization: TextCapitalization.words,
        ),
        const SizedBox(height: 14),
        // pedido do app: a cliente pagou por estes itens e valores; para devolver parte, "Devolver valor" no card
        if (_shop)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(16)),
            child: Text.rich(
              TextSpan(children: [
                TextSpan(text: '${number(widget.existing!.qty)}× ${widget.existing!.product}\n', style: const TextStyle(fontWeight: FontWeight.w800)),
                TextSpan(
                  text: 'Pedido do app: item e valor não mudam aqui. Para devolver parte do valor, use "Devolver valor" no card.',
                  style: TextStyle(color: c.muted, fontSize: 13.5),
                ),
              ]),
              style: const TextStyle(height: 1.4),
            ),
          )
        else ...[
        SelectField<int?>(
          label: 'Receita (opcional)',
          hint: 'Preenche o produto e o preço sugerido.',
          value: _recipe,
          items: [
            const DropdownMenuItem(value: null, child: Text('Produto avulso')),
            for (final r in widget.recipes)
              DropdownMenuItem(
                value: r.id,
                child: Text(
                  '${r.name} · ${money(r.pricing?.rounded ?? 0)}',
                  overflow: TextOverflow.ellipsis,
                ),
              ),
          ],
          onChanged: _pickRecipe,
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Produto',
          controller: _product,
          maxLength: 100,
          capitalization: TextCapitalization.sentences,
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: NumberField(
                label: 'Quantidade',
                controller: _qty,
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: NumberField(
                label: 'Preço unitário (R\$)',
                controller: _price,
                onChanged: (_) => setState(() {}),
              ),
            ),
          ],
        ),
        ],
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: c.pinkTint,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'Total do pedido',
                  style: TextStyle(
                    color: c.pinkStrong,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                money(total),
                style: display(context, size: 22, color: c.pinkStrong),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: _dateField('Data do pedido', _orderDate, () async {
                final d = await _pickDate(_orderDate);
                if (d != null) setState(() => _orderDate = d);
              }),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _dateField('Entrega', _delivery, () async {
                final d = await _pickDate(_delivery, allowEmpty: true);
                if (d != null) setState(() => _delivery = d);
              }),
            ),
          ],
        ),
        const SizedBox(height: 14),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: SelectField<String>(
                label: 'Status',
                value: _status,
                items: [
                  // pedido do app só é cancelado pelo botão "Cancelar pedido" do card (pede o motivo e devolve o Pix)
                  for (final s in orderStatus)
                    if (!_shop || s.$1 != 'cancelado' || _status == 'cancelado') DropdownMenuItem(value: s.$1, child: Text(s.$2)),
                ],
                onChanged: (v) => setState(() => _status = v ?? 'novo'),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: SelectField<String>(
                label: 'Pagamento',
                value: _payment,
                items: [
                  for (final s in orderPayment)
                    DropdownMenuItem(value: s.$1, child: Text(s.$2)),
                ],
                onChanged: (v) => setState(() => _payment = v ?? 'pendente'),
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        AppTextField(
          label: 'Observações',
          controller: _notes,
          maxLength: 300,
          maxLines: 3,
        ),
        const SizedBox(height: 20),
        Row(
          children: [
            Expanded(
              child: BusyButton(
                label: 'Cancelar',
                outlined: true,
                onPressed: () => Navigator.pop(context),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: BusyButton(
                label: 'Salvar pedido',
                busy: _busy,
                onPressed: _save,
              ),
            ),
          ],
        ),
      ],
    );
  }
}
