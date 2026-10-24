import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

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

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final store = context.watch<VendaStore>();
    final groups = store.menu.groups.where((g) => g.items.isNotEmpty).toList();
    return Scaffold(
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
      body: CustomScrollView(
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
                itemBuilder: (_, i) => _ProductTile(g.items[i], onTap: () => _open(g.items[i])),
              ),
            ),
          ],
          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

class _ProductTile extends StatelessWidget {
  const _ProductTile(this.item, {required this.onTap});
  final MenuItem item;
  final VoidCallback onTap;
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
          Text(money(item.price), style: display(context, size: 22, color: c.pinkStrong)),
        ],
      ),
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
