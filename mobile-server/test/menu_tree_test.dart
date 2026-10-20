import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_gestao/features/admin/menu_tree.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

Widget _app() => MaterialApp(
  theme: buildTheme(AppColors.light, Brightness.light),
  home: Scaffold(
    body: SingleChildScrollView(child: MenuTree(tile: (i) => Text(i.title))),
  ),
);

void main() {
  testWidgets('seções na ordem do painel web, todas abertas na primeira vez', (t) async {
    SharedPreferences.setMockInitialValues({});
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(menuSections.map((s) => s.title), [
      'Produção e catálogo',
      'Estoque e compras',
      'Vendas e pedidos',
      'Clientes',
      'Planejamento e finanças',
    ]);
    for (final s in menuSections) {
      expect(find.text(s.title.toUpperCase()), findsOneWidget);
      for (final i in s.items) {
        expect(find.text(i.title), findsOneWidget);
      }
    }
  });

  testWidgets('recolher esconde os itens e a escolha fica guardada', (t) async {
    SharedPreferences.setMockInitialValues({});
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    await t.tap(find.text('CLIENTES'));
    await t.pumpAndSettle();
    expect(find.text('Engajamento'), findsNothing);
    expect(find.text('Cupons'), findsOneWidget);
    expect((await SharedPreferences.getInstance()).getStringList('rk.menu.recolhidas'), ['clientes']);

    // uma nova abertura do app já nasce com a seção recolhida
    await t.pumpWidget(const SizedBox());
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('Engajamento'), findsNothing);
  });
}
