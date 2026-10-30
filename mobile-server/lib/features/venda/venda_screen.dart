import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/pix/venda_cart.dart';
import '../../core/pix/venda_store.dart';
import 'pix_config_screen.dart';
import 'pix_qr_screen.dart';

/// Venda Presencial: o cardápio inteiro já na tela (lido do aparelho, sem loading). Tocar num produto pergunta a quantidade e abre o QR do
/// Pix estático com o total. Nada aqui depende de internet; a cópia do cardápio só é renovada em segundo plano.
class VendaScreen extends StatefulWidget {
  const VendaScreen({super.key});
  @override
  State<VendaScreen> createState() => _VendaScreenState();
}

class _VendaScreenState extends State<VendaScreen> {
  /// Carrinho aberto: mostra o painel por cima e o "+" nos produtos.
  bool _cartOpen = false;
  bool _generating = false;

  @override
  void initState() {
    super.initState();
    // renova a cópia do cardápio depois do primeiro desenho; sem internet, não muda nada
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final store = context.read<VendaStore>();
      final api = context.read<Session>().ownerApi;
      store.refreshMenu(api);
      store.flushEvents(api); // manda ao histórico o que foi gerado sem internet
    });
  }

  Future<void> _open(MenuItem item) async {
    final cfg = context.read<VendaStore>().config;
    if (cfg == null) {
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const PixConfigScreen()));
      return;
    }
    final qty = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (_) => _QuantitySheet(item),
    );
    if (qty == null || !mounted) return;
    final store = context.read<VendaStore>();
    final api = context.read<Session>().ownerApi;
    // em centavos inteiros, para o total do Pix não herdar erro de ponto flutuante
    final amount = (item.price * 100).round() * qty / 100;
    final title = qty > 1 ? '$qty× ${item.name}' : item.name;
    store.report('qr_gerado', title: title, amount: amount).then((_) => store.flushEvents(api));
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => PixQrScreen(title: title, amount: amount, config: cfg)));
  }

  void _addToCart(MenuItem item) {
    if (!context.read<VendaStore>().cart.add(item)) {
      Toast.error('"${item.name}" não tem preço e não pode entrar no carrinho.');
    }
  }

  Future<void> _clearCart() async {
    final cart = context.read<VendaStore>().cart;
    if (cart.isEmpty) return;
    if (await confirmDialog(context, title: 'Limpar o carrinho?', message: 'Todos os itens saem do carrinho.', confirmLabel: 'Limpar')) {
      cart.clear();
    }
  }

  /// Um Pix só com o total do carrinho (o mesmo QR estático da venda de um produto).
  Future<void> _generateCart() async {
    final store = context.read<VendaStore>();
    final cart = store.cart;
    final cfg = store.config;
    if (cart.isEmpty || _generating) return; // trava o duplo toque
    if (cfg == null) {
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const PixConfigScreen()));
      return;
    }
    _generating = true;
    final api = context.read<Session>().ownerApi;
    final amount = cart.total;
    store.report('qr_gerado', title: cart.summary(), amount: amount).then((_) => store.flushEvents(api));
    final items = [for (final l in cart.lines) (name: l.name, qty: l.qty, subtotal: l.subtotalCents / 100)];
    try {
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => PixQrScreen(
            title: 'Carrinho · ${cart.itemCount} ${cart.itemCount == 1 ? 'item' : 'itens'}',
            amount: amount,
            config: cfg,
            items: items,
            onFinish: cart.clear,
          ),
        ),
      );
    } finally {
      _generating = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final store = context.watch<VendaStore>();
    final groups = store.menu.groups.where((g) => g.items.isNotEmpty).toList();
    final panelH = (MediaQuery.sizeOf(context).height * 0.55).clamp(320.0, 460.0);
    return PopScope(
      canPop: !_cartOpen,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) setState(() => _cartOpen = false); // o voltar do Android fecha o carrinho antes de sair da tela
      },
      child: Scaffold(
      appBar: AppBar(
        title: const Text('Venda presencial'),
        // aberta pelo widget não há para onde voltar: o botão leva ao painel (que pede a biometria)
        leading: context.canPop()
            ? null
            : IconButton(
                tooltip: 'Painel',
                icon: const Icon(Icons.space_dashboard_outlined),
                onPressed: () => context.go('/painel/mais'),
              ),
        actions: [
          IconButton(
            tooltip: 'Chave Pix',
            icon: const Icon(Icons.key_rounded),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const PixConfigScreen())),
          ),
        ],
      ),
      body: Stack(children: [
      CustomScrollView(
        slivers: [
          if (store.config == null)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
                child: AppCard(
                  color: c.pinkTint,
                  onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const PixConfigScreen())),
                  child: Row(children: [
                    Icon(Icons.key_rounded, color: c.pinkStrong),
                    const SizedBox(width: 12),
                    const Expanded(child: Text('Cadastre sua chave Pix para gerar os QR Codes.', style: TextStyle(fontWeight: FontWeight.w700))),
                    Icon(Icons.chevron_right_rounded, color: c.muted),
                  ]),
                ),
              ),
            ),
          if (groups.isEmpty)
            SliverFillRemaining(
              hasScrollBody: false,
              child: Padding(
                padding: const EdgeInsets.all(28),
                child: Center(
                  child: Text(
                    store.refreshing
                        ? 'Carregando o cardápio...'
                        : 'O cardápio ainda não foi baixado neste celular.\nAbra esta tela uma vez com internet.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: c.muted, height: 1.4),
                  ),
                ),
              ),
            ),
          for (final g in groups) ...[
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverToBoxAdapter(child: SectionTitle(g.name)),
            ),
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverGrid.builder(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 10, crossAxisSpacing: 10, mainAxisExtent: 112),
                itemCount: g.items.length,
                itemBuilder: (_, i) => _ProductTile(g.items[i], onTap: () => _open(g.items[i]), onAdd: _cartOpen ? () => _addToCart(g.items[i]) : null),
              ),
            ),
          ],
          // com o carrinho aberto, sobra espaço para o último card não ficar escondido atrás do painel
          SliverToBoxAdapter(child: SizedBox(height: _cartOpen ? panelH + 96 : 96)),
        ],
      ),
      ListenableBuilder(
        listenable: store.cart,
        builder: (context, _) => _cartLayer(context, store.cart, panelH),
      ),
      ]),
    ));
  }

  /// Botão flutuante do carrinho (com o selo de itens) e o painel que sobe por cima da lista.
  Widget _cartLayer(BuildContext context, VendaCart cart, double panelH) {
    final c = context.c;
    const slide = Duration(milliseconds: 260);
    return Positioned.fill(
      child: Stack(children: [
        AnimatedPositioned(
          duration: slide,
          curve: Curves.easeOutCubic,
          right: 16,
          bottom: (_cartOpen ? panelH : 0) + 16,
          child: Badge(
            isLabelVisible: cart.itemCount > 0,
            label: Text('${cart.itemCount}'),
            child: FloatingActionButton(
              tooltip: _cartOpen ? 'Fechar o carrinho' : 'Abrir o carrinho',
              backgroundColor: c.pinkStrong,
              foregroundColor: Colors.white,
              onPressed: () => setState(() => _cartOpen = !_cartOpen),
              child: Icon(_cartOpen ? Icons.keyboard_arrow_down_rounded : Icons.shopping_cart_outlined),
            ),
          ),
        ),
        AnimatedPositioned(
          duration: slide,
          curve: Curves.easeOutCubic,
          left: 0,
          right: 0,
          bottom: _cartOpen ? 0 : -panelH - 24,
          height: panelH,
          child: IgnorePointer(
            ignoring: !_cartOpen,
            child: _CartPanel(
              cart: cart,
              onClose: () => setState(() => _cartOpen = false),
              onClear: _clearCart,
              onGenerate: _generateCart,
            ),
          ),
        ),
      ]),
    );
  }
}

class _ProductTile extends StatelessWidget {
  const _ProductTile(this.item, {required this.onTap, this.onAdd});
  final MenuItem item;
  final VoidCallback onTap;

  /// Só com o carrinho aberto: adiciona 1 unidade ao carrinho.
  final VoidCallback? onAdd;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return AppCard(
      onTap: onTap,
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15, height: 1.2)),
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(child: FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Text(money(item.price), style: display(context, size: 22, color: c.pinkStrong)))),
              if (onAdd != null)
                IconButton.filled(
                  tooltip: 'Adicionar ${item.name} ao carrinho',
                  visualDensity: VisualDensity.compact,
                  style: IconButton.styleFrom(backgroundColor: c.pinkStrong, foregroundColor: Colors.white),
                  icon: const Icon(Icons.add_rounded),
                  onPressed: onAdd,
                ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Painel do carrinho: itens com quantidade, total e o botão que gera o Pix. Arrastar para baixo recolhe.
class _CartPanel extends StatelessWidget {
  const _CartPanel({required this.cart, required this.onClose, required this.onClear, required this.onGenerate});
  final VendaCart cart;
  final VoidCallback onClose, onClear, onGenerate;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Material(
      color: c.surface,
      elevation: 12,
      borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
      clipBehavior: Clip.antiAlias,
      child: SafeArea(
        top: false,
        child: Column(children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onVerticalDragEnd: (d) {
              if ((d.primaryVelocity ?? 0) > 250) onClose();
            },
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 4, 0),
              child: Column(children: [
                Container(width: 36, height: 4, decoration: BoxDecoration(color: c.line, borderRadius: BorderRadius.circular(4))),
                Row(children: [
                  Expanded(
                    child: Text(
                      cart.isEmpty ? 'Carrinho' : 'Carrinho · ${cart.itemCount} ${cart.itemCount == 1 ? 'item' : 'itens'}',
                      style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                    ),
                  ),
                  if (!cart.isEmpty) TextButton(onPressed: onClear, child: const Text('Limpar carrinho')),
                  IconButton(tooltip: 'Fechar o carrinho', icon: const Icon(Icons.keyboard_arrow_down_rounded), onPressed: onClose),
                ]),
              ]),
            ),
          ),
          Expanded(
            child: cart.isEmpty
                ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text('Toque no + dos produtos para montar a venda.', textAlign: TextAlign.center, style: TextStyle(color: c.muted))))
                : ListView(padding: const EdgeInsets.fromLTRB(16, 0, 16, 8), children: [for (final l in cart.lines) _CartRow(key: ValueKey(l.id), line: l, cart: cart)]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: Column(children: [
              Row(children: [
                Expanded(child: Text('Total · ${cart.itemCount} ${cart.itemCount == 1 ? 'item' : 'itens'}', style: TextStyle(color: c.muted, fontWeight: FontWeight.w700))),
                Text(money(cart.total), style: display(context, size: 26, color: c.pinkStrong)),
              ]),
              const SizedBox(height: 8),
              BusyButton(label: 'Gerar PIX', icon: Icons.qr_code_2_rounded, onPressed: cart.isEmpty ? null : onGenerate),
            ]),
          ),
        ]),
      ),
    );
  }
}

/// Uma linha do carrinho: nome, preço, − / quantidade editável / +, subtotal e remover.
class _CartRow extends StatefulWidget {
  const _CartRow({super.key, required this.line, required this.cart});
  final CartLine line;
  final VendaCart cart;
  @override
  State<_CartRow> createState() => _CartRowState();
}

class _CartRowState extends State<_CartRow> {
  late final _ctl = TextEditingController(text: '${widget.line.qty}');
  final _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    _focus.addListener(() {
      if (!_focus.hasFocus) _settle();
    });
  }

  @override
  void didUpdateWidget(_CartRow old) {
    super.didUpdateWidget(old);
    // o − e o + mudam a quantidade por fora: o campo acompanha (menos enquanto a pessoa digita)
    if (!_focus.hasFocus && _ctl.text != '${widget.line.qty}') _ctl.text = '${widget.line.qty}';
  }

  @override
  void dispose() {
    _ctl.dispose();
    _focus.dispose();
    super.dispose();
  }

  /// Ao terminar de digitar: vazio volta ao que era; 0 tira o item.
  void _settle() {
    final n = int.tryParse(_ctl.text);
    if (n == null) {
      _ctl.text = '${widget.line.qty}';
    } else {
      widget.cart.setQty(widget.line.id, n);
      if (n > 0) _ctl.text = '${widget.line.qty}';
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final l = widget.line;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(l.name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('${money(l.cents / 100)} cada', style: TextStyle(color: c.muted, fontSize: 12.5)),
          ]),
        ),
        IconButton(
          tooltip: 'Menos uma unidade',
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.remove_circle_outline_rounded),
          onPressed: () => widget.cart.setQty(l.id, l.qty - 1),
        ),
        SizedBox(
          width: 44,
          child: TextField(
            controller: _ctl,
            focusNode: _focus,
            textAlign: TextAlign.center,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(2)],
            decoration: const InputDecoration(isDense: true, contentPadding: EdgeInsets.symmetric(vertical: 8)),
            onSubmitted: (_) => _settle(),
          ),
        ),
        IconButton(
          tooltip: 'Mais uma unidade',
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.add_circle_outline_rounded),
          onPressed: l.qty < VendaCart.maxQty ? () => widget.cart.setQty(l.id, l.qty + 1) : null,
        ),
        SizedBox(width: 74, child: Text(money(l.subtotalCents / 100), textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800))),
        IconButton(
          tooltip: 'Tirar ${l.name} do carrinho',
          visualDensity: VisualDensity.compact,
          icon: Icon(Icons.delete_outline_rounded, color: c.bad),
          onPressed: () => widget.cart.remove(l.id),
        ),
      ]),
    );
  }
}

/// Escolha da quantidade de unidades do produto; devolve o número escolhido ao confirmar.
class _QuantitySheet extends StatefulWidget {
  const _QuantitySheet(this.item);
  final MenuItem item;
  @override
  State<_QuantitySheet> createState() => _QuantitySheetState();
}

class _QuantitySheetState extends State<_QuantitySheet> {
  static const _max = 99;
  int _qty = 1;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final total = (widget.item.price * 100).round() * _qty / 100;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.item.name, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
            const SizedBox(height: 4),
            Text('${money(widget.item.price)} cada', style: TextStyle(color: c.muted, fontWeight: FontWeight.w600)),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton.filledTonal(
                  tooltip: 'Menos uma unidade',
                  icon: const Icon(Icons.remove_rounded),
                  onPressed: _qty > 1 ? () => setState(() => _qty--) : null,
                ),
                SizedBox(width: 72, child: Text('$_qty', textAlign: TextAlign.center, style: display(context, size: 36, color: c.pinkStrong))),
                IconButton.filledTonal(
                  tooltip: 'Mais uma unidade',
                  icon: const Icon(Icons.add_rounded),
                  onPressed: _qty < _max ? () => setState(() => _qty++) : null,
                ),
              ],
            ),
            const SizedBox(height: 16),
            BusyButton(
              label: 'Gerar QR · ${money(total)}',
              icon: Icons.qr_code_2_rounded,
              onPressed: () => Navigator.of(context).pop(_qty),
            ),
          ],
        ),
      ),
    );
  }
}
