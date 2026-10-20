import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/prospection.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';
import 'package:raykookie_gestao/core/prospection_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'helpers.dart';

Map<String, Object?> recipe(int id, String name, {double perUnit = 2, double rounded = 5}) => {
      'id': id, 'name': name, 'category_id': 1, 'yield_units': 10, 'margin_pct': 100, 'prep_hours': 0, 'notes': '', 'items': <Object?>[],
      'pricing': {'perUnit': perUnit, 'rounded': rounded, 'total': perUnit * 10},
    };

Future<ProspectionStore> open(WidgetTester t, {ProspectionStore? store, String at = '/painel/prospeccao'}) async {
  t.view.physicalSize = const Size(430, 6000); // alta: a tela é uma ListView preguiçosa, assim tudo é montado
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  final be = FakeBackend({
    'GET /api/me': (_) => (200, {'id': 1, 'username': 'dona-teste'}),
    'GET /api/recipes': (_) => (200, [recipe(1, 'Brigadeiro'), recipe(2, 'Bolo de pote', perUnit: 6, rounded: 10)]),
    'GET /api/categories': (_) => (200, [{'id': 1, 'kind': 'recipe', 'name': 'Doces', 'icon': 'cake', 'color': '#ff7aa2', 'uses': 2}]),
  });
  final tokens = MemoryTokenStore()..write('owner_token', 'tok-dono');
  final session = be.session(store: tokens);
  await session.load();
  final lock = BiometricLock(session: session, auth: FakeBiometric(isAvailable: false));
  final s = store ?? ProspectionStore();
  await t.pumpWidget(GestaoApp(session: session, theme: ThemeController(), lock: lock, prospection: s, initialLocation: at));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  return s;
}

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  group('ProspectionStore', () {
    ProspectItem item(String id) => ProspectItem(id: id, name: 'Item $id', qty: 10, cost: 2, price: 5);

    test('salvar exige nome e depois atualiza a mesma simulação', () {
      final s = ProspectionStore()..add(item('a'));
      expect(s.save(), isFalse);
      s.setName('Festa');
      var n = 0;
      expect(s.save(newId: () => 'id${++n}'), isTrue);
      expect(s.simulations.length, 1);
      s.add(item('b'));
      expect(s.save(), isTrue);
      expect(s.simulations.length, 1, reason: 'atualiza em vez de criar outra');
      expect(s.simulations.first.items.length, 2);
    });

    test('salvar como cópia cria outra, com "(cópia)" no nome', () {
      final s = ProspectionStore()..add(item('a'))..setName('Festa');
      var n = 0;
      s.save(newId: () => 'id${++n}');
      s.save(asCopy: true, newId: () => 'id${++n}');
      expect(s.simulations.map((e) => e.name), containsAll(['Festa', 'Festa (cópia)']));
    });

    test('abrir, duplicar e excluir', () {
      final s = ProspectionStore()..add(item('a'))..setName('Festa');
      s.save(newId: () => 'x');
      s.startNew();
      expect(s.items, isEmpty);
      s.open(s.simulations.first);
      expect(s.items.single.id, 'a');
      expect(s.savedId, 'x');
      s.duplicate(s.simulations.first, newId: () => 'y');
      expect(s.simulations.length, 2);
      s.delete('x');
      expect(s.simulations.single.id, 'y');
      expect(s.savedId, isNull);
    });

    test('rascunho e simulações sobrevivem a fechar o app', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final s = ProspectionStore(prefs)
        ..add(item('a'))
        ..setGlobals(const ProspectGlobals(freight: 9, tiers: [Tier(10, 5)]))
        ..setName('Festa');
      s.save(newId: () => 'x');
      final again = ProspectionStore(prefs);
      expect(again.items.single.name, 'Item a');
      expect(again.globals.freight, 9);
      expect(again.globals.tiers.single.pct, 5);
      expect(again.simulations.single.name, 'Festa');
      expect(again.savedId, 'x');
    });

    test('dado estragado no aparelho não derruba: começa vazio', () async {
      SharedPreferences.setMockInitialValues({'prospeccao_rascunho': '{lixo', 'prospeccao_simulacoes': '[1,2'});
      final s = ProspectionStore(await SharedPreferences.getInstance());
      expect(s.items, isEmpty);
      expect(s.simulations, isEmpty);
    });
  });

  group('tela', () {
    testWidgets('lista os produtos do cadastro, seleciona todos e calcula os totais', (t) async {
      final s = await open(t);
      expect(find.text('Brigadeiro'), findsOneWidget);
      expect(find.text('Bolo de pote'), findsOneWidget);
      await t.tap(find.text('Selecionar todos'));
      await t.pumpAndSettle();
      expect(s.items.length, 2);
      expect(find.text('2 selecionados'), findsOneWidget);
      // 1 un de cada: bruto 5 + 10 = R$ 15,00
      expect(find.text('R\$ 15,00'), findsWidgets);
      await t.ensureVisible(find.text('Limpar seleção'));
      await t.tap(find.text('Limpar seleção'));
      await t.pumpAndSettle();
      expect(s.items, isEmpty);
    });

    testWidgets('o estado é mantido ao sair da aba e voltar', (t) async {
      final s = await open(t);
      await t.tap(find.text('Brigadeiro'));
      await t.pumpAndSettle();
      expect(s.items.single.id, 'r-1');
      // sai para o "Mais" e volta
      final router = GoRouter.of(t.element(find.byType(Scaffold).first));
      router.go('/painel/mais');
      await t.pumpAndSettle();
      router.push('/painel/prospeccao');
      await t.pumpAndSettle();
      expect(find.text('1 selecionado'), findsOneWidget);
    });

    testWidgets('alerta quando a margem fica abaixo do mínimo', (t) async {
      final s = await open(t);
      s.add(ProspectItem(id: 'm-1', name: 'Bolo caro', qty: 1, cost: 9, price: 10));
      await t.pumpAndSettle();
      expect(find.textContaining('Abaixo do mínimo'), findsWidgets);
      s.add(ProspectItem(id: 'm-2', name: 'Prejuízo', qty: 1, cost: 20, price: 10));
      await t.pumpAndSettle();
      expect(find.textContaining('Prejuízo'), findsWidgets);
    });

    testWidgets('Gerar tabela final mostra título, data e a linha TOTAL', (t) async {
      final s = await open(t);
      s.setName('Festa da Ana');
      s.add(ProspectItem(id: 'm-1', name: 'Docinho', qty: 100, cost: 1, price: 3, discountMode: DiscountMode.pct, discount: 10));
      await t.pumpAndSettle();
      await t.ensureVisible(find.text('Gerar tabela final'));
      await t.tap(find.text('Gerar tabela final'));
      await t.pumpAndSettle();
      expect(find.text('Tabela final'), findsOneWidget);
      expect(find.text('Festa da Ana'), findsOneWidget);
      expect(find.textContaining('Gerada em'), findsOneWidget);
      expect(find.text('TOTAL'), findsOneWidget);
      expect(find.textContaining('R\$ 270,00'), findsWidgets); // 100 × 3 − 10%
    });

    testWidgets('exportar sem produtos avisa em vez de gerar arquivo vazio', (t) async {
      await open(t);
      await t.ensureVisible(find.text('Exportar CSV'));
      await t.tap(find.text('Exportar CSV'));
      await t.pump();
      expect(find.text('Escolha ao menos um produto.'), findsOneWidget);
      await t.pumpAndSettle(const Duration(seconds: 5));
    });
  });
}
