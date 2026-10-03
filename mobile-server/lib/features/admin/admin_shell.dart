import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/session.dart';

/// Navegação do painel: Início, Estoque, Receitas, Pedidos e Mais (metas, categorias, custos, devoluções, tema, sair).
/// O "Mais" mostra quantas devoluções aguardam decisão (conferido ao abrir e a cada troca de aba).
class AdminShell extends StatefulWidget {
  const AdminShell({super.key, required this.shell});
  final StatefulNavigationShell shell;
  @override
  State<AdminShell> createState() => _AdminShellState();
}

class _AdminShellState extends State<AdminShell> {
  int _waiting = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _refresh());
  }

  Future<void> _refresh() async {
    try {
      final r = await context.read<Session>().ownerApi.get('/api/devolucoes?status=aguardando') as Map;
      final n = ((r['counts'] as Map?)?['aguardando'] as num?)?.toInt() ?? 0;
      if (mounted && n != _waiting) setState(() => _waiting = n);
    } catch (_) {
      // sem rede: o selo fica como estava
    }
  }

  @override
  Widget build(BuildContext context) {
    final shell = widget.shell;
    return Scaffold(
      body: SafeArea(bottom: false, child: shell),
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) {
          shell.goBranch(i, initialLocation: i == shell.currentIndex);
          _refresh();
        },
        destinations: [
          const NavigationDestination(
            icon: Icon(Icons.space_dashboard_outlined),
            selectedIcon: Icon(Icons.space_dashboard_rounded),
            label: 'Início',
          ),
          const NavigationDestination(
            icon: Icon(Icons.inventory_2_outlined),
            selectedIcon: Icon(Icons.inventory_2_rounded),
            label: 'Estoque',
          ),
          const NavigationDestination(
            icon: Icon(Icons.restaurant_menu_outlined),
            selectedIcon: Icon(Icons.restaurant_menu_rounded),
            label: 'Receitas',
          ),
          const NavigationDestination(
            icon: Icon(Icons.receipt_long_outlined),
            selectedIcon: Icon(Icons.receipt_long_rounded),
            label: 'Pedidos',
          ),
          NavigationDestination(
            icon: Badge(isLabelVisible: _waiting > 0, label: Text('$_waiting'), child: const Icon(Icons.menu_rounded)),
            selectedIcon: Badge(isLabelVisible: _waiting > 0, label: Text('$_waiting'), child: const Icon(Icons.menu_open_rounded)),
            label: 'Mais',
            tooltip: _waiting > 0 ? 'Mais · $_waiting devolução(ões) aguardando' : 'Mais',
          ),
        ],
      ),
    );
  }
}
