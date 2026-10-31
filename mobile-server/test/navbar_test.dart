import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';

import 'helpers.dart';

/// Abre o painel numa tela estreita (um celular comum de 360 px) com a barra de baixo à mostra.
Future<void> openNarrow(WidgetTester t, double width) async {
  t.view.physicalSize = Size(width, 800);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
  final be = FakeBackend({'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'})});
  final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
  await s.load();
  await t.pumpWidget(GestaoApp(session: s, theme: ThemeController(), lock: BiometricLock(session: s, auth: FakeBiometric(isAvailable: false))));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
}

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  Finder bar(String Function(String) match) => find.descendant(
    of: find.byType(NavigationBar),
    matching: find.byWidgetPredicate((w) => w is Text && w.data != null && match(w.data!) != ''),
  );

  for (final width in [320.0, 360.0, 412.0]) {
    testWidgets('barra de baixo ($width px): nenhum nome quebra em duas linhas', (t) async {
      await openNarrow(t, width);
      for (final name in ['Início', 'Ingred', 'Receitas', 'Pedidos', 'Mais']) {
        final label = bar((d) => d.startsWith(name) ? d : '');
        expect(label, findsOneWidget, reason: name);
        // uma linha de 12 px de fonte tem menos de 20 de altura; duas linhas passam disso
        expect(t.renderObject<RenderParagraph>(label).size.height, lessThan(20), reason: '$name quebrou em duas linhas');
      }
    });
  }

  testWidgets('"Ingredientes" que não cabe termina em reticências e o nome inteiro fica no tooltip', (t) async {
    await openNarrow(t, 320);
    final label = bar((d) => d.startsWith('Ingred') ? d : '');
    final text = (t.widget(label) as Text).data!;
    expect(text, endsWith('…'));
    expect(text.length, lessThan('Ingredientes'.length));
    expect(find.byTooltip('Estoque de ingredientes'), findsOneWidget);
  });

  testWidgets('em tela larga o nome aparece inteiro, sem reticências', (t) async {
    await openNarrow(t, 600);
    expect(bar((d) => d == 'Ingredientes' ? d : ''), findsOneWidget);
  });
}
