import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';

import 'helpers.dart';

Map<String, Object?> entry(int id, {String details = 'Estoque de Farinha: 1000 para 800 g', String category = 'estoque'}) => {
      'id': id,
      'occurred_at': '2026-10-18T15:30:00-03:00',
      'user_id': 1,
      'user_name': 'dona-teste',
      'role': 'dona',
      'action': 'ajustou',
      'category': category,
      'location': 'Gestão > Estoque',
      'record_type': 'ingredient',
      'record_id': '7',
      'old_value': {'stock_qty': 1000, 'name': 'Farinha'},
      'new_value': {'stock_qty': 800, 'name': 'Farinha'},
      'details': details,
      'ip': '203.0.113.9',
    };

Future<FakeBackend> open(WidgetTester t, {int total = 2, int perPage = 30, bool denied = false}) async {
  useBigPhone(t);
  final be = FakeBackend({
    'GET /api/me': (_) => (200, {'id': 1, 'username': 'dona-teste'}),
    'GET /api/historico': (req) {
      if (denied) return (403, {'error': 'Sem permissão.'});
      final page = int.parse(req.url.queryParameters['page'] ?? '1');
      final start = (page - 1) * perPage;
      final ids = [for (var i = start + 1; i <= total && i <= start + perPage; i++) i];
      return (200, {'items': [for (final i in ids) entry(i, details: 'Registro $i')], 'total': total, 'page': page, 'per_page': perPage});
    },
  });
  final store = MemoryTokenStore()..write('owner_token', 'tok-dono');
  final session = be.session(store: store);
  await session.load();
  final lock = BiometricLock(session: session, auth: FakeBiometric(isAvailable: false));
  await t.pumpWidget(GestaoApp(session: session, theme: ThemeController(), lock: lock, initialLocation: '/painel/historico'));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  return be;
}

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  testWidgets('lista os registros do mais recente ao mais antigo com quem, onde e o resumo', (t) async {
    await open(t);
    expect(find.text('Histórico'), findsOneWidget);
    expect(find.text('Registro 1'), findsOneWidget);
    expect(find.text('Registro 2'), findsOneWidget);
    expect(find.text('Ajustou · Estoque'), findsNWidgets(2));
    expect(find.textContaining('dona-teste · Gestão > Estoque'), findsNWidgets(2));
    expect(find.text('2 registros'), findsOneWidget);
  });

  testWidgets('tocar abre o antes e o depois só dos campos que mudaram', (t) async {
    await open(t, total: 1);
    await t.tap(find.text('Registro 1'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Antes → depois'), findsOneWidget);
    expect(find.text('stock_qty'), findsOneWidget);
    expect(find.text('Antes: 1000'), findsOneWidget);
    expect(find.text('Depois: 800'), findsOneWidget);
    expect(find.text('name'), findsNothing); // não mudou
    expect(find.text('203.0.113.9'), findsOneWidget);
  });

  testWidgets('filtros vão para o servidor: período, categoria e busca', (t) async {
    final be = await open(t);
    await t.tap(find.text('7 dias'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    await t.enterText(find.byType(TextField), 'farinha');
    await t.testTextInput.receiveAction(TextInputAction.search);
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    final last = be.calls.where((c) => c.startsWith('GET /api/historico')).last;
    expect(last, contains('from='));
    expect(last, contains('q=farinha'));
    expect(last, contains('per_page=30'));
  });

  testWidgets('sem permissão (servidor nega): a tela mostra o aviso e nenhum registro', (t) async {
    await open(t, denied: true);
    expect(find.text('Sem permissão.'), findsOneWidget);
    expect(find.text('Registro 1'), findsNothing);
    expect(find.text('Tentar de novo'), findsOneWidget);
  });

  testWidgets('mais de uma página: "Carregar mais" busca a página seguinte', (t) async {
    final be = await open(t, total: 5, perPage: 2);
    expect(find.text('Registro 3'), findsNothing);
    final more = find.text('Carregar mais');
    await t.scrollUntilVisible(more, 200, scrollable: find.byType(Scrollable).last);
    await t.tap(more);
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(be.calls.last, contains('page=2'));
  });
}
