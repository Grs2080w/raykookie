import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/menu_extras.dart';
import 'package:raykookie_common/ui/widgets.dart';
import 'brand.dart';
import 'card_brands.dart';
import 'card_fields.dart';
import 'three_ds_challenge.dart';

class NewOrderScreen extends StatelessWidget {
  const NewOrderScreen({super.key, this.initialItem});
  final String? initialItem;

  @override
  Widget build(BuildContext context) {
    final s = context.read<Session>();
    return BrandScope(
      child: Scaffold(
        appBar: const BrandAppBar(showBack: true, title: 'Novo pedido'),
        body: FutureBuilder<(Menu, PickupInfo)>(
          future: () async {
            final menu = Menu.fromJson(await s.customerApi.get('/api/menu') as Map<String, dynamic>);
            // o endereço de retirada é um extra: sem ele o pedido segue normal ("Retirada no local")
            final pickup = await s.customerApi.get('/api/pickup').then((r) => PickupInfo.fromJson(r as Map<String, dynamic>)).catchError((_) => PickupInfo());
            return (menu, pickup);
          }(),
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
            if (snap.hasError) return const Center(child: Padding(padding: EdgeInsets.all(24), child: Text('Não conseguimos carregar o cardápio agora. Tente de novo.')));
            final (menu, pickup) = snap.data!;
            if (menu.items.isEmpty) {
              return const Padding(padding: EdgeInsets.all(20), child: EmptyState(icon: Icons.cake_outlined, title: 'Cardápio em preparação', text: 'Ainda não há itens disponíveis para encomenda. Volte em breve!'));
            }
            return _OrderForm(menu: menu, pickup: pickup, initialItem: initialItem);
          },
        ),
      ),
    );
  }
}

class _OrderForm extends StatefulWidget {
  const _OrderForm({required this.menu, required this.pickup, this.initialItem});
  final Menu menu;
  final PickupInfo pickup;
  final String? initialItem;
  @override
  State<_OrderForm> createState() => _OrderFormState();
}

class _OrderFormState extends State<_OrderForm> {
  late final Map<String, int> _cart;
  /// Cupom do último passo: o código digitado e a resposta do servidor (desconto e total), nulo = sem cupom.
  final _couponCtl = TextEditingController();
  Map<String, dynamic>? _coupon;
  bool _couponBusy = false;
  final _form = OrderForm();
  final _ctl = <String, TextEditingController>{};
  bool _busy = false;
  int _stepIdx = 0; // etapa atual (0 a 4)
  final _scroll = ScrollController();
  late final String _minDate;
  final _cardKey = GlobalKey<CardFieldsState>();
  bool _cardEnabled = false;
  String? _cardPublicKey;
  List<CardBrand> _brands = const [];

  TextEditingController _c(String k, [String initial = '']) => _ctl.putIfAbsent(k, () => TextEditingController(text: initial));

  @override
  void initState() {
    super.initState();
    _cart = {if (widget.initialItem != null && widget.menu.items.any((m) => m.id == widget.initialItem)) widget.initialItem!: 1};
    final p = context.read<Session>().profile;
    _minDate = addDaysIso(todayIso(), AppConfig.leadDays);
    context.read<Session>().customerApi.get('/api/payment-config').then((r) {
      final d = Map<String, dynamic>.from(r as Map);
      if (mounted) setState(() { _cardEnabled = d['cardEnabled'] == true; _cardPublicKey = d['publicKey'] as String?; _brands = parseBrands(d['brands']); });
    }).catchError((_) {});
    _form
      ..contactName = p?.name ?? ''
      ..phone = formatPhone(p?.phone ?? '')
      ..cep = formatCep(p?.cep ?? '')
      ..street = p?.street ?? ''
      ..number = p?.number ?? ''
      ..complement = p?.complement ?? ''
      ..district = p?.district ?? ''
      ..city = p?.city ?? ''
      ..fulfillment = (p?.street.isNotEmpty ?? false) ? 'entrega' : 'retirada';
    _c('name', _form.contactName);
    _c('phone', _form.phone);
    _c('cep', _form.cep);
    _c('street', _form.street);
    _c('number', _form.number);
    _c('complement', _form.complement);
    _c('district', _form.district);
    _c('city', _form.city);
  }

  @override
  void dispose() {
    _scroll.dispose();
    _couponCtl.dispose();
    for (final c in _ctl.values) {
      c.dispose();
    }
    super.dispose();
  }

  void _step(String id, int d) {
    final next = (_cart[id] ?? 0) + d;
    if (next > maxQty) return Toast.warn('Para mais de $maxQty unidades do mesmo item, fale com a gente pelo WhatsApp.');
    setState(() {
      _cart[id] = next < 0 ? 0 : next;
      // o total mudou: o cupom precisa ser conferido de novo
      _coupon = null;
      _form.couponCode = '';
    });
  }

  /// "Aplicar": o servidor confere o código com o total do cardápio dele e devolve o desconto (ou o motivo).
  Future<void> _applyCoupon() async {
    final code = _couponCtl.text.trim();
    if (code.isEmpty) return Toast.error('Digite o código do cupom.');
    setState(() => _couponBusy = true);
    try {
      final r = await context.read<Session>().customerApi.post('/api/cliente/cupom', {'code': code, 'items': _form.toJson(_cart)['items']});
      setState(() {
        _coupon = Map<String, dynamic>.from(r as Map);
        _form.couponCode = '${_coupon!['code']}';
      });
      Toast.ok('Cupom aplicado');
    } on ApiException catch (e) {
      setState(() {
        _coupon = null;
        _form.couponCode = '';
      });
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _couponBusy = false);
    }
  }

  void _removeCoupon() => setState(() {
        _coupon = null;
        _form.couponCode = '';
        _couponCtl.clear();
      });

  /// Total a pagar: com o desconto do cupom, quando há.
  double _payable(double total) => _coupon == null ? total : ((_coupon!['total'] as num?) ?? total).toDouble();

  Future<void> _lookupCep(String masked) async {
    final d = onlyDigits(masked, 8);
    if (d.length != 8) return;
    try {
      final r = await http.get(Uri.parse('https://viacep.com.br/ws/$d/json/')).timeout(const Duration(seconds: 8));
      final j = jsonDecode(r.body) as Map<String, dynamic>;
      if (j['erro'] != null) return Toast.warn('Não encontramos esse CEP. Preencha o endereço na mão.');
      setState(() {
        if (_c('street').text.isEmpty) _c('street').text = _form.street = (j['logradouro'] ?? '') as String;
        if (_c('district').text.isEmpty) _c('district').text = _form.district = (j['bairro'] ?? '') as String;
        if (_c('city').text.isEmpty) _c('city').text = _form.city = (j['localidade'] ?? '') as String;
      });
    } catch (_) {/* sem internet ou bloqueado: a pessoa preenche na mão */}
  }

  Future<void> _pickDate() async {
    final min = DateTime.parse(_minDate);
    final d = await showDatePicker(
      context: context, initialDate: _form.scheduledFor.isEmpty ? min : DateTime.parse(_form.scheduledFor), firstDate: min,
      lastDate: min.add(const Duration(days: 178)), helpText: 'Escolha a data', cancelText: 'Cancelar', confirmText: 'Escolher',
    );
    if (d != null) setState(() => _form.scheduledFor = todayIso(d));
  }

  void _goTo(int step) {
    setState(() => _stepIdx = step);
    if (_scroll.hasClients) _scroll.jumpTo(0);
  }

  /// "Continuar": confere a etapa atual antes de avançar.
  /// Índice da etapa "Forma de pagamento" (a 4ª).
  static const _paymentStep = 3;

  /// Na etapa do pagamento com cartão, tokeniza antes de validar: a validação da etapa exige o token.
  Future<void> _next() async {
    if (_stepIdx == _paymentStep && _form.paymentMethod == 'cartao' && _cardKey.currentState != null) {
      setState(() => _busy = true);
      final ok = await _tokenizeCard();
      if (mounted) setState(() => _busy = false);
      if (!ok) return;
    }
    final err = validateOrderStep(_stepIdx, _form, _cart, widget.menu.items);
    if (err != null) return Toast.error(err);
    _goTo(_stepIdx + 1);
  }

  /// Tokeniza o cartão do formulário e guarda no pedido. `false` = não validou (o aviso já apareceu).
  Future<bool> _tokenizeCard() async {
    final t = await _cardKey.currentState?.tokenize();
    if (t == null) return false;
    _form
      ..cardToken = t.cardToken
      ..cardPaymentMethodId = t.paymentMethodId
      ..cpf = t.cpf
      ..installments = t.installments
      ..cardIssuerId = t.issuerId
      ..cardPaymentTypeId = t.typeId
      ..deviceId = t.deviceId;
    return true;
  }

  Future<void> _submit() async {
    if (_form.paymentMethod == 'cartao' && _form.cardToken.isEmpty && !await _tokenizeCard()) return;
    final err = validateOrder(_form, _cart, widget.menu.items);
    if (err != null) return Toast.error(err);
    if (!mounted) return;
    final session = context.read<Session>();
    setState(() => _busy = true);
    try {
      final r = Map<String, dynamic>.from(await session.customerApi.post('/api/cliente/pedidos', _form.toJson(_cart)) as Map);
      await session.refreshProfile();
      var card = r['card'] is Map ? Map<String, dynamic>.from(r['card'] as Map) : null;
      // o banco pode pedir a confirmação do cartão (3DS): mostra aqui mesmo e confere o resultado sem cobrar de novo
      if (card != null && mounted) card = await withChallenge(context, card, () => session.customerApi.get('/api/cliente/pedidos/${r['id']}/cartao'));
      if (card != null && card['pending'] == true) Toast.warn(cardInReview);
      if (card != null && card['approved'] != true && card['pending'] != true) Toast.error('${card['reason'] ?? 'Pagamento recusado'}');
      if (mounted) context.go('/cliente/pedidos?novo=${r['id']}');
    } on ApiException catch (e) {
      // o token do cartão é de uso único: a nova tentativa gera outro com o mesmo formulário
      _form.cardToken = '';
      Toast.error(e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Uma etapa do pedido: só aparece quando é a etapa atual.
  Widget _section(int n, String title, {String? hint, required List<Widget> children}) {
    if (n != _stepIdx + 1) return const SizedBox.shrink();
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(top: 22),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Semantics(header: true, child: Text(title, style: display(context, size: 30, height: 1.1))),
        if (hint != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(hint, style: TextStyle(color: c.muted, fontSize: 15, height: 1.4))),
        const SizedBox(height: 18),
        ...children,
      ]),
    );
  }

  /// "Passo N de 5" com a barra de progresso.
  Widget _progress() {
    final c = context.c;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Text('Passo ${_stepIdx + 1} de ${orderSteps.length}', style: TextStyle(color: c.muted, fontSize: 13, fontWeight: FontWeight.w700)),
        const Spacer(),
        Text(orderSteps[_stepIdx], style: TextStyle(color: c.muted, fontSize: 13, fontWeight: FontWeight.w700)),
      ]),
      const SizedBox(height: 8),
      Semantics(
        label: 'Passo ${_stepIdx + 1} de ${orderSteps.length}',
        child: Row(children: [
          for (var i = 0; i < orderSteps.length; i++) ...[
            if (i > 0) const SizedBox(width: 6),
            Expanded(child: AnimatedContainer(duration: const Duration(milliseconds: 200), height: 5, decoration: BoxDecoration(color: i <= _stepIdx ? c.pink : c.line, borderRadius: BorderRadius.circular(3)))),
          ],
        ]),
      ),
    ]);
  }

  Widget _choice({required bool selected, required String title, String? hint, String? tag, required VoidCallback onTap}) {
    final c = context.c;
    return Semantics(
      inMutuallyExclusiveGroup: true,
      selected: selected,
      button: true,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150), width: double.infinity, margin: const EdgeInsets.only(bottom: 10),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(color: selected ? c.pinkTint : c.surface, borderRadius: BorderRadius.circular(20), border: Border.all(color: selected ? c.pink : c.line, width: 2)),
          child: Row(children: [
            Container(
              width: 22, height: 22, alignment: Alignment.center,
              decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: selected ? c.pink : c.line, width: 2), color: c.surface),
              child: selected ? Container(width: 10, height: 10, decoration: BoxDecoration(shape: BoxShape.circle, color: c.pink)) : null,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                if (hint != null && hint.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 2), child: Text(hint, style: TextStyle(color: c.muted, fontSize: 13))),
              ]),
            ),
            if (tag != null) AppBadge(tag, tone: 'pink', dot: false),
          ]),
        ),
      ),
    );
  }

  /// Resumo final: itens, entrega e pagamento, para conferir antes de finalizar.
  Widget _summary(List<MenuItem> items, double total) {
    final c = context.c;
    final lines = [for (final m in items) if ((_cart[m.id] ?? 0) > 0) (m, _cart[m.id]!)];
    if (lines.isEmpty) return const SizedBox.shrink();
    final win = timeWindows.where((w) => w.id == _form.timeWindow).map((w) => w.label).firstOrNull;
    final when = [if (_form.scheduledFor.isNotEmpty) dateBR(_form.scheduledFor), if (win != null) win].join(' · ');
    final how = _form.fulfillment == 'entrega' ? 'Entrega' : 'Retirada';
    return Padding(
      padding: const EdgeInsets.only(top: 26),
      child: AppCard(
        padding: const EdgeInsets.all(18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('SEU PEDIDO', style: TextStyle(color: c.muted, fontWeight: FontWeight.w800, fontSize: 12.5, letterSpacing: 1)),
          const SizedBox(height: 8),
          for (final (m, n) in lines)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 5),
              child: Row(children: [
                Expanded(child: Text.rich(TextSpan(children: [TextSpan(text: '$n× ', style: const TextStyle(fontWeight: FontWeight.w800)), TextSpan(text: m.name)]))),
                Text(money(m.price * n), style: TextStyle(color: c.muted)),
              ]),
            ),
          Divider(color: c.line, height: 20),
          Text([how, if (when.isNotEmpty) when].join(' · '), style: TextStyle(color: c.muted, fontSize: 13.5)),
          if (_form.fulfillment == 'retirada' && !widget.pickup.isEmpty)
            Padding(padding: const EdgeInsets.only(top: 2), child: Text(widget.pickup.address, style: TextStyle(color: c.muted, fontSize: 13.5))),
          const SizedBox(height: 12),
          if (_coupon == null)
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Expanded(
                child: AppTextField(
                  key: const Key('coupon_field'),
                  label: 'Cupom de desconto',
                  controller: _couponCtl,
                  hint: 'Ex.: NATAL20',
                  capitalization: TextCapitalization.characters,
                ),
              ),
              const SizedBox(width: 8),
              BusyButton(key: const Key('coupon_apply'), label: 'Aplicar', expand: false, busy: _couponBusy, onPressed: _applyCoupon),
            ])
          else ...[
            Row(children: [
              Expanded(child: Text('Subtotal', style: TextStyle(color: c.muted))),
              Text(money(total), style: TextStyle(color: c.muted)),
            ]),
            Row(children: [
              Expanded(
                child: Text(
                  'Cupom ${_coupon!['code']} (-${fieldNum((_coupon!['percent'] as num).toDouble())}%)',
                  style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700),
                ),
              ),
              Text('-${money(((_coupon!['discount'] as num?) ?? 0).toDouble())}', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700)),
              IconButton(tooltip: 'Remover cupom', onPressed: _removeCoupon, icon: const Icon(Icons.close_rounded, size: 18)),
            ]),
          ],
          const SizedBox(height: 6),
          Row(children: [
            const Expanded(child: Text('Total', style: TextStyle(fontWeight: FontWeight.w800))),
            Text(money(_payable(total)), key: const Key('order_total'), style: display(context, size: 24)),
          ]),
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final api = context.read<Session>().customerApi;
    final total = cartTotal(_cart, widget.menu.items);
    final count = _cart.values.fold<int>(0, (s, v) => s + v);
    final multi = widget.menu.groups.length > 1;
    return PopScope(
      canPop: _stepIdx == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _goTo(_stepIdx - 1);
      },
      child: Column(children: [
      Expanded(
        child: SingleChildScrollView(
          controller: _scroll,
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 24),
          child: Centered(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _progress(),
              _section(1, 'O que você quer?', hint: 'Toque em + para adicionar ao pedido.', children: [
                for (final g in widget.menu.groups) ...[
                  if (multi) Padding(padding: const EdgeInsets.only(top: 6, bottom: 8), child: Text(g.name.toUpperCase(), style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w800, fontSize: 12.5, letterSpacing: 1))),
                  for (final m in g.items) _menuRow(m, api),
                ],
              ]),
              _section(2, 'Seus dados', hint: 'O acompanhamento do pedido é feito pelo app. Usamos o telefone só se precisarmos falar com você.', children: [
                AppCard(
                  child: Column(children: [
                    AppTextField(label: 'Nome completo', controller: _c('name'), maxLength: 80, capitalization: TextCapitalization.words, onChanged: (v) => _form.contactName = v, autofillHints: const [AutofillHints.name]),
                    const SizedBox(height: 14),
                    AppTextField(
                      label: 'Telefone com WhatsApp', helper: 'Com DDD. Só usamos se for necessário.', controller: _c('phone'), maxLength: 15, hint: '(11) 91234-5678', keyboardType: TextInputType.phone,
                      formatters: [MaskFormatter(format: formatPhone, warning: 'No telefone só entram números. Ex.: (11) 91234-5678')],
                      onChanged: (v) => _form.phone = v, autofillHints: const [AutofillHints.telephoneNumberNational],
                    ),
                    const SizedBox(height: 14),
                    AppTextField(label: 'E-mail', controller: TextEditingController(text: context.read<Session>().profile?.email ?? ''), enabled: false),
                  ]),
                ),
              ]),
              _section(3, 'Como você quer receber?', children: [
                for (final o in fulfillmentOptions) _choice(selected: _form.fulfillment == o.id, title: o.label, hint: o.hint, onTap: () => setState(() => _form.fulfillment = o.id)),
                if (_form.fulfillment == 'retirada' && !widget.pickup.isEmpty)
                  Container(
                    width: double.infinity,
                    margin: const EdgeInsets.only(bottom: 10),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(18), border: Border.all(color: c.pink.withValues(alpha: 0.3))),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Icon(Icons.place_outlined, color: c.pinkStrong),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Text(widget.pickup.label.isEmpty ? 'Endereço de retirada' : 'Endereço de retirada: ${widget.pickup.label}', style: const TextStyle(fontWeight: FontWeight.w800)),
                          const SizedBox(height: 2),
                          Text(widget.pickup.address, style: const TextStyle(fontSize: 15, height: 1.35)),
                          if (widget.pickup.instructions.isNotEmpty) Text(widget.pickup.instructions, style: TextStyle(color: c.muted, fontSize: 13.5)),
                        ]),
                      ),
                    ]),
                  ),
                if (_form.fulfillment == 'entrega')
                  AppCard(
                    child: Column(children: [
                      AppTextField(
                        label: 'CEP', controller: _c('cep'), maxLength: 9, hint: '00000-000', keyboardType: TextInputType.number,
                        formatters: [MaskFormatter(format: formatCep, warning: 'No CEP só entram números. Ex.: 01310-100', allowed: r'[\d\-]')],
                        onChanged: (v) { _form.cep = v; _lookupCep(v); },
                      ),
                      const SizedBox(height: 14),
                      Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Expanded(flex: 3, child: AppTextField(label: 'Rua', controller: _c('street'), maxLength: 100, onChanged: (v) => _form.street = v, capitalization: TextCapitalization.words)),
                        const SizedBox(width: 12),
                        Expanded(flex: 2, child: AppTextField(label: 'Número', controller: _c('number'), maxLength: 10, hint: 'S/N', onChanged: (v) => _form.number = v)),
                      ]),
                      const SizedBox(height: 14),
                      AppTextField(label: 'Complemento (opcional)', controller: _c('complement'), maxLength: 60, hint: 'Apto, bloco…', onChanged: (v) => _form.complement = v),
                      const SizedBox(height: 14),
                      AppTextField(label: 'Bairro', controller: _c('district'), maxLength: 60, onChanged: (v) => _form.district = v, capitalization: TextCapitalization.words),
                      const SizedBox(height: 14),
                      AppTextField(label: 'Cidade', controller: _c('city'), maxLength: 60, onChanged: (v) => _form.city = v, capitalization: TextCapitalization.words),
                    ]),
                  ),
                const SizedBox(height: 12),
                AppCard(
                  child: Column(children: [
                    LabeledField(
                      label: _form.fulfillment == 'entrega' ? 'Data da entrega' : 'Data da retirada',
                      hint: 'Encomendas com pelo menos ${AppConfig.leadDaysLabel}.',
                      child: InkWell(
                        onTap: _pickDate, borderRadius: BorderRadius.circular(14),
                        child: InputDecorator(
                          decoration: const InputDecoration(suffixIcon: Icon(Icons.calendar_month_outlined)),
                          child: Text(_form.scheduledFor.isEmpty ? 'Escolha a data' : dateBR(_form.scheduledFor), style: TextStyle(color: _form.scheduledFor.isEmpty ? c.muted : c.ink, fontSize: 16)),
                        ),
                      ),
                    ),
                    const SizedBox(height: 14),
                    SelectField<String>(
                      label: 'Período', value: _form.timeWindow,
                      items: [const DropdownMenuItem(value: '', child: Text('Escolha…')), for (final w in timeWindows) DropdownMenuItem(value: w.id, child: Text(w.label))],
                      onChanged: (v) => setState(() => _form.timeWindow = v ?? ''),
                    ),
                  ]),
                ),
              ]),
              _section(4, 'Forma de pagamento', hint: 'Com Pix, o código aparece logo depois que você finalizar o pedido.', children: [
                for (final p in paymentMethods.where((p) => p.id != 'cartao' || _cardEnabled))
                  _choice(selected: _form.paymentMethod == p.id, title: p.label, hint: p.hint, tag: p.id == 'pix' ? 'Mais rápido' : null, onTap: () => setState(() => _form.paymentMethod = p.id)),
                if (_form.paymentMethod == 'dinheiro')
                  AppCard(child: NumberField(label: 'Precisa de troco para quanto? (opcional)', helper: 'Deixe em branco se tiver o valor certinho.', hint: 'Ex.: 100', controller: _c('change'), onChanged: (v) => _form.changeFor = v)),
              ]),
              // O cartão fica montado (só escondido fora da etapa de pagamento): assim o que foi digitado não se
              // perde ao voltar/avançar, e o "Finalizar pedido" consegue gerar outro token depois de uma falha.
              if (_form.paymentMethod == 'cartao' && _cardPublicKey != null)
                Offstage(offstage: _stepIdx != _paymentStep, child: AppCard(child: CardFields(key: _cardKey, publicKey: _cardPublicKey!, brands: _brands, amount: _payable(total)))),
              _section(5, 'Algo mais?', children: [
                AppTextField(label: 'Observações (opcional)', helper: 'Sabor, decoração, alergias, recado no bolo, ponto de referência…', controller: _c('notes'), maxLength: 300, maxLines: 4, onChanged: (v) => _form.notes = v, capitalization: TextCapitalization.sentences),
              ]),
              if (_stepIdx >= 3) _summary(widget.menu.items, total),
            ]),
          ),
        ),
      ),
      // Barra fixa: total e botão sempre ao alcance do polegar
      SafeArea(
        top: false,
        child: Container(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
          decoration: BoxDecoration(color: c.surface, border: Border(top: BorderSide(color: c.line))),
          child: Centered(
            child: Row(children: [
              // nos passos 2 a 5, o botão Voltar fica sempre à vista (a tela pode ter sido aberta sem histórico)
              if (_stepIdx > 0) ...[
                IconButton.outlined(onPressed: () => _goTo(_stepIdx - 1), icon: const Icon(Icons.arrow_back_rounded), tooltip: 'Voltar', style: IconButton.styleFrom(minimumSize: const Size(52, 52))),
                const SizedBox(width: 12),
              ],
              Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                Text(count == 0 ? 'Nenhum item' : '$count ${count == 1 ? 'item' : 'itens'}', style: TextStyle(color: c.muted, fontSize: 12)),
                Text(money(total), style: display(context, size: 26)),
              ]),
              const SizedBox(width: 18),
              Expanded(child: BusyButton(label: _stepIdx == orderSteps.length - 1 ? 'Finalizar pedido' : 'Continuar', busy: _busy, onPressed: _stepIdx == orderSteps.length - 1 ? _submit : _next)),
            ]),
          ),
        ),
      ),
    ]),
    );
  }

  Widget _menuRow(MenuItem m, ApiClient api) {
    final c = context.c;
    final n = _cart[m.id] ?? 0;
    return Container(
      margin: const EdgeInsets.only(bottom: 10), padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(18), border: Border.all(color: n > 0 ? c.pink : c.line, width: 2)),
      child: Row(children: [
        PhotoTile(kind: photoKind(m.name, m.category), url: m.image == null ? null : api.absolute(m.image!), size: 64, radius: 14),
        const SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(m.name, style: const TextStyle(fontWeight: FontWeight.w800, height: 1.2)),
          Text(money(m.price), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
          if (weightLabel(m.weightG).isNotEmpty) Text('${weightLabel(m.weightG)} cada', style: TextStyle(color: c.muted, fontSize: 13)),
          if (m.tags.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Wrap(spacing: 4, runSpacing: 4, children: [
                for (final t in m.tags)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                    decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(99)),
                    child: Text(menuTagLabel(t), style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700, fontSize: 10)),
                  ),
              ]),
            ),
          MenuItemExtras(m),
        ])),
        if (n > 0) ...[
          IconButton.outlined(onPressed: () => _step(m.id, -1), icon: const Icon(Icons.remove_rounded), tooltip: 'Diminuir ${m.name}', style: IconButton.styleFrom(minimumSize: const Size(44, 44))),
          SizedBox(width: 30, child: Text('$n', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18))),
        ],
        IconButton.filled(onPressed: () => _step(m.id, 1), icon: const Icon(Icons.add_rounded), tooltip: 'Adicionar ${m.name}', style: IconButton.styleFrom(minimumSize: const Size(44, 44), backgroundColor: c.pink, foregroundColor: c.onPink)),
      ]),
    );
  }
}
