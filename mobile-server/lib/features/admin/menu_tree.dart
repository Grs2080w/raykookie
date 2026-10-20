import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:raykookie_common/ui/theme.dart';

/// Uma entrada do menu "Mais": ícone, título, descrição e a rota que ela abre.
class MenuItem {
  const MenuItem(this.icon, this.title, this.subtitle, this.route);
  final IconData icon;
  final String title;
  final String subtitle;
  final String route;
}

/// Seção do menu: título discreto e as entradas dentro dela.
class MenuSection {
  const MenuSection(this.id, this.title, this.items);
  final String id;
  final String title;
  final List<MenuItem> items;
}

/// Árvore do menu "Mais", na mesma ordem e nos mesmos grupos da sidebar do painel web (`frontend/lib/nav.ts`).
/// Início, Estoque, Receitas e Pedidos não aparecem aqui: já são as abas da barra inferior.
const menuSections = [
  MenuSection('producao', 'Produção e catálogo', [
    MenuItem(Icons.restaurant_menu_outlined, 'Cardápio', 'O que aparece na landing page, ingredientes e combos', '/painel/cardapio'),
    MenuItem(Icons.sell_outlined, 'Categorias', 'Ingredientes, receitas e gastos', '/painel/categorias'),
  ]),
  MenuSection('estoque', 'Estoque e compras', [
    MenuItem(Icons.storefront_outlined, 'Fornecedores', 'Sites e lojas onde você compra, com preços', '/painel/fornecedores'),
  ]),
  MenuSection('vendas', 'Vendas e pedidos', [
    MenuItem(Icons.local_offer_outlined, 'Cupons', 'Códigos de desconto para os clientes', '/painel/cupons'),
    MenuItem(Icons.qr_code_2_rounded, 'Venda Presencial', 'Pix estático por produto, funciona sem internet', '/painel/venda-presencial'),
    MenuItem(Icons.undo_rounded, 'Devoluções', 'Pedidos de devolução e Pix devolvidos', '/painel/devolucoes'),
    MenuItem(Icons.place_outlined, 'Endereço de retirada', 'Onde o cliente retira os pedidos', '/painel/enderecos'),
    MenuItem(Icons.notifications_active_outlined, 'Som de novos pedidos', '', '/painel/som'),
  ]),
  MenuSection('clientes', 'Clientes', [
    MenuItem(Icons.reviews_outlined, 'Feedback dos clientes', 'Nota, mensagem e fotos enviadas no site e no app', '/painel/feedback'),
    MenuItem(Icons.insights_outlined, 'Engajamento', 'O que os clientes veem e até onde chegam no pedido', '/painel/engajamento'),
    MenuItem(Icons.privacy_tip_outlined, 'Privacidade dos clientes', 'Solicitações sobre dados (LGPD)', '/painel/privacidade'),
  ]),
  MenuSection('planejamento', 'Planejamento e finanças', [
    MenuItem(Icons.track_changes_rounded, 'Metas de venda', 'Defina o alvo do mês e acompanhe', '/painel/metas'),
    MenuItem(Icons.settings_outlined, 'Custos e ajustes', 'Valor da hora e gastos do mês', '/painel/custos'),
    MenuItem(Icons.history_rounded, 'Histórico', 'Quem mudou estoque, receitas, preços e cupons', '/painel/historico'),
    MenuItem(Icons.calculate_outlined, 'Prospecção', 'Planeje o preço por quantidade, com desconto e margem', '/painel/prospeccao'),
  ]),
];

/// Seções recolhidas (lembradas entre sessões).
const _prefsKey = 'rk.menu.recolhidas';

/// Mostra [menuSections] como grupos recolhíveis; cada entrada é desenhada por [tile]. Começa tudo aberto.
class MenuTree extends StatefulWidget {
  const MenuTree({super.key, required this.tile});
  final Widget Function(MenuItem item) tile;
  @override
  State<MenuTree> createState() => _MenuTreeState();
}

class _MenuTreeState extends State<MenuTree> {
  Set<String> _closed = {};

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((p) {
      if (mounted) setState(() => _closed = (p.getStringList(_prefsKey) ?? const []).toSet());
    });
  }

  Future<void> _toggle(String id) async {
    setState(() => _closed.contains(id) ? _closed.remove(id) : _closed.add(id));
    final p = await SharedPreferences.getInstance();
    await p.setStringList(_prefsKey, _closed.toList());
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final s in menuSections) ...[
          Semantics(
            button: true,
            expanded: !_closed.contains(s.id),
            label: s.title,
            excludeSemantics: true,
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: () => _toggle(s.id),
              child: ConstrainedBox(
                constraints: const BoxConstraints(minHeight: 48),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          s.title.toUpperCase(),
                          style: TextStyle(color: c.muted, fontSize: 12, fontWeight: FontWeight.w700, letterSpacing: 1),
                        ),
                      ),
                      AnimatedRotation(
                        turns: _closed.contains(s.id) ? -0.25 : 0,
                        duration: const Duration(milliseconds: 200),
                        child: Icon(Icons.expand_more_rounded, color: c.muted),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          AnimatedSize(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            alignment: Alignment.topCenter,
            child: _closed.contains(s.id)
                ? const SizedBox(width: double.infinity)
                : Column(children: [for (final i in s.items) widget.tile(i)]),
          ),
        ],
      ],
    );
  }
}
