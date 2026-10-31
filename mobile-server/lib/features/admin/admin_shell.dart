import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/session.dart';

/// Navegação do painel: Início, Estoque de ingredientes, Receitas, Pedidos e Mais (metas, categorias, custos, devoluções, tema, sair).
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
      bottomNavigationBar: LayoutBuilder(
        builder: (context, box) {
          // cada aba ocupa 1/5 da largura; o rótulo que não couber nela termina em reticências
          String fit(String text) => _fitLabel(context, text, box.maxWidth / 5);
          return NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) {
          shell.goBranch(i, initialLocation: i == shell.currentIndex);
          _refresh();
        },
        destinations: [
          NavigationDestination(
            icon: Icon(Icons.space_dashboard_outlined),
            selectedIcon: Icon(Icons.space_dashboard_rounded),
            label: fit('Início'),
            tooltip: 'Início',
          ),
          NavigationDestination(
            icon: Icon(Icons.inventory_2_outlined),
            selectedIcon: Icon(Icons.inventory_2_rounded),
            label: fit('Ingredientes'),
            tooltip: 'Estoque de ingredientes',
          ),
          NavigationDestination(
            icon: Icon(Icons.restaurant_menu_outlined),
            selectedIcon: Icon(Icons.restaurant_menu_rounded),
            label: fit('Receitas'),
            tooltip: 'Receitas',
          ),
          NavigationDestination(
            icon: Icon(Icons.receipt_long_outlined),
            selectedIcon: Icon(Icons.receipt_long_rounded),
            label: fit('Pedidos'),
            tooltip: 'Pedidos',
          ),
          NavigationDestination(
            icon: Badge(isLabelVisible: _waiting > 0, label: Text('$_waiting'), child: const Icon(Icons.menu_rounded)),
            selectedIcon: Badge(isLabelVisible: _waiting > 0, label: Text('$_waiting'), child: const Icon(Icons.menu_open_rounded)),
            label: fit('Mais'),
            tooltip: _waiting > 0 ? 'Mais · $_waiting devolução(ões) aguardando' : 'Mais',
          ),
        ],
          );
        },
      ),
    );
  }
}

/// Corta o nome com "…" quando ele não cabe em [width]. O Flutter só aceita o rótulo da aba como texto simples e, sem
/// espaço, quebra a palavra em duas linhas; medindo com o mesmo estilo e a mesma escala de texto da barra dá para
/// decidir antes. O nome inteiro continua no tooltip da aba.
String _fitLabel(BuildContext context, String text, double width) {
  final style = Theme.of(context).navigationBarTheme.labelTextStyle?.resolve({WidgetState.selected});
  // a barra limita a escala do texto dos rótulos a 1,3 (veja NavigationDestination)
  final scaler = MediaQuery.textScalerOf(context).clamp(maxScaleFactor: 1.3);
  bool fits(String candidate) {
    final painter = TextPainter(text: TextSpan(text: candidate, style: style), textDirection: TextDirection.ltr, textScaler: scaler, maxLines: 1)..layout();
    final ok = painter.width <= width - 4; // folga para o arredondamento
    painter.dispose();
    return ok;
  }

  if (fits(text)) return text;
  var cut = text;
  while (cut.length > 1 && !fits('$cut…')) {
    cut = cut.substring(0, cut.length - 1);
  }
  return '$cut…';
}
