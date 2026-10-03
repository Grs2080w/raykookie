import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';
import 'package:raykookie_gestao/core/receipt.dart';
import 'package:raykookie_gestao/features/admin/receipt_screen.dart';

import 'helpers.dart';

const _link = 'http://www.nfce.fazenda.sp.gov.br/qrcode?p=35260912345678000190650010000123451000123456|2|1|1|ABC';

Map<String, dynamic> _item(int i, String name, double qty, String unit, double price, {Map<String, dynamic>? match, Map<String, dynamic>? add, Map<String, dynamic>? create}) => {
      'index': i, 'name': name, 'code': '', 'qty': qty, 'unit': unit, 'unit_price': price, 'total': qty * price, 'match': match, 'add': add,
      'create': create ?? {'name': name, 'unit': 'un', 'package_size': 1, 'package_cost': price, 'stock_qty': qty},
    };

Map<String, dynamic> preview({String? already}) => {
      'key': '35260912345678000190650010000123451000123456', 'store': 'SUPERMERCADO BOM PRECO', 'date': '2026-09-12', 'total': 40.0, 'already_imported': already,
      'items': [
        _item(0, 'LEITE COND MOÇA 395G', 3, 'UN', 5.49, match: {'ingredient_id': 1, 'name': 'Leite condensado', 'exact': true}, add: {'add_qty': 3, 'package_cost': 5.49, 'note': '3 embalagem(ns)'}),
        _item(1, 'ACUCAR REFINADO UNIAO 1KG', 1, 'UN', 4.2, match: {'ingredient_id': 2, 'name': 'Açúcar', 'exact': false}, add: {'add_qty': 1, 'package_cost': 4.2, 'note': '1 embalagem(ns)'},
            create: {'name': 'Acucar refinado uniao 1kg', 'unit': 'g', 'package_size': 1000, 'package_cost': 4.2, 'stock_qty': 1}),
        _item(2, 'CREME DE LEITE 200G', 2, 'UN', 3.29, create: {'name': 'Creme de leite 200g', 'unit': 'g', 'package_size': 200, 'package_cost': 3.29, 'stock_qty': 2}),
        _item(3, 'EMBALAGEM PLASTICA', 1, 'UN', 9.9),
      ],
    };

const _ingredients = [
  {'id': 1, 'category_id': null, 'name': 'Leite condensado', 'unit': 'g', 'stock_qty': 1, 'package_size': 395, 'package_cost': 5, 'min_stock': 1, 'supplier': '', 'notes': ''},
  {'id': 2, 'category_id': null, 'name': 'Açúcar', 'unit': 'g', 'stock_qty': 2, 'package_size': 1000, 'package_cost': 4, 'min_stock': 1, 'supplier': '', 'notes': ''},
];

FakeBackend backend({Map<String, dynamic>? nota, int readStatus = 200, Object? readBody}) => FakeBackend({
      'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
      'GET /api/ingredients': (_) => (200, _ingredients),
      'GET /api/categories?kind=ingredient': (_) => (200, []),
      'POST /api/nota/ler': (_) => (readStatus, readBody ?? nota ?? preview()),
      'POST /api/nota/importar': (_) => (201, {'added': 1, 'created': 1}),
    });

Future<FakeBackend> openFlow(WidgetTester t, FakeBackend be, {PageFetcher? fetch, String link = _link}) async {
  useBigPhone(t);
  ReceiptScreen.debugScanner = (onCode) => Center(child: TextButton(onPressed: () => onCode(link), child: const Text('simular leitura')));
  ReceiptScreen.debugFetcher = fetch ?? (uri) async => '<html>${'x' * 300}</html>';
  addTearDown(() {
    ReceiptScreen.debugScanner = null;
    ReceiptScreen.debugFetcher = null;
  });
  final store = MemoryTokenStore()..write('owner_token', 'tok');
  final s = be.session(store: store);
  await s.load();
  await t.pumpWidget(GestaoApp(session: s, theme: ThemeController(), lock: BiometricLock(session: s, auth: FakeBiometric(isAvailable: false)), initialLocation: '/painel/estoque'));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  await t.tap(find.text('Ler nota'));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  return be;
}

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  group('link e página da nota', () {
    test('só aceita links de portais .gov.br e força https', () {
      expect(nfceUri(_link).toString(), startsWith('https://www.nfce.fazenda.sp.gov.br/qrcode?p='));
      expect(nfceUri('https://www.fazenda.pr.gov.br/nfce/qrcode?p=1'), isNotNull);
      expect(nfceUri('https://gov.br.evil.com/x'), isNull);
      expect(nfceUri('https://evilgov.br/x'), isNull);
      expect(nfceUri('ftp://x.gov.br/a'), isNull);
      expect(nfceUri('texto qualquer'), isNull);
      expect(nfceUri(''), isNull);
    });
    test('decodifica a página em UTF-8 ou, se não for, em Latin-1', () {
      expect(decodePage(utf8.encode('AÇÚCAR')), 'AÇÚCAR');
      expect(decodePage(latin1.encode('AÇÚCAR')), 'AÇÚCAR');
    });
  });

  testWidgets('estoque -> ler nota -> conferir -> lançar: soma o que existe e pergunta sobre o novo', (t) async {
    final be = await openFlow(t, backend());
    expect(find.textContaining('Aponte a câmera'), findsOneWidget);
    await t.tap(find.text('simular leitura'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));

    // o app mandou o link e a página baixada para o servidor interpretar
    final sent = be.bodies['POST /api/nota/ler'] as Map;
    expect(sent['url'], _link);
    expect((sent['html'] as String).startsWith('<html>'), isTrue);

    expect(find.text('SUPERMERCADO BOM PRECO'), findsOneWidget);
    expect(find.text('12/09/2026 · 4 itens · total R\$ 40,00'), findsOneWidget);
    expect(find.text('Somar ao estoque'), findsOneWidget);
    expect(find.text('Leite condensado'), findsOneWidget);
    expect(find.textContaining('+3 embalagens'), findsOneWidget);
    expect(find.text('Não estão no estoque (3)'), findsOneWidget);
    expect(find.textContaining('Parece ser o “Açúcar”'), findsOneWidget);

    // enquanto houver itens sem resposta, não deixa lançar
    expect(find.widgetWithText(FilledButton, 'Responda os itens novos (3)'), findsOneWidget);
    expect(t.widget<FilledButton>(find.widgetWithText(FilledButton, 'Responda os itens novos (3)')).onPressed, isNull);

    Future<void> tapVisible(Finder f) async {
      await t.ensureVisible(f);
      await t.pumpAndSettle();
      await t.tap(f);
    }
    await tapVisible(find.text('Somar em Açúcar'));                        // sugestão: soma no que já tem
    await tapVisible(find.widgetWithText(ChoiceChip, 'Aceitar').first);        // creme de leite: aceita criar
    await tapVisible(find.widgetWithText(ChoiceChip, 'Recusar').last);     // embalagem plástica: recusa
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.widgetWithText(FilledButton, 'Lançar no estoque (3)'), findsOneWidget);

    await t.tap(find.text('Lançar no estoque (3)'));
    await t.pumpAndSettle(const Duration(milliseconds: 200));

    final body = be.bodies['POST /api/nota/importar'] as Map;
    expect(body['key'], '35260912345678000190650010000123451000123456');
    final items = (body['items'] as List).cast<Map>();
    expect(items, hasLength(3));
    expect(items[0], {'action': 'add', 'ingredient_id': 1, 'add_qty': 3, 'package_cost': 5.49, 'item_name': 'LEITE COND MOÇA 395G'});
    expect(items[1], {'action': 'add', 'ingredient_id': 2, 'add_qty': 1, 'package_cost': 4.2, 'item_name': 'ACUCAR REFINADO UNIAO 1KG'});
    expect(items[2], {'action': 'create', 'name': 'Creme de leite 200g', 'unit': 'g', 'package_size': 200, 'package_cost': 3.29, 'stock_qty': 2});

    // volta para o estoque, que recarrega
    expect(find.text('Estoque de ingredientes'), findsOneWidget);
    expect(be.calls.where((c) => c == 'GET /api/ingredients').length, 2);
  });

  testWidgets('dá para desmarcar o que já existe, não atualizar o preço, aceitar/recusar todos e ajustar um novo', (t) async {
    final be = await openFlow(t, backend());
    await t.tap(find.text('simular leitura'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));

    await t.tap(find.byType(Switch).first);                                   // não somar o leite
    await t.tap(find.widgetWithText(SwitchListTile, 'Atualizar o preço das embalagens'));
    await t.ensureVisible(find.text('Recusar todos'));
    await t.tap(find.text('Recusar todos'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Nenhum item selecionado'), findsOneWidget);

    await t.ensureVisible(find.widgetWithText(ChoiceChip, 'Aceitar').first);
    await t.pumpAndSettle();
    await t.tap(find.widgetWithText(ChoiceChip, 'Aceitar').first);            // creme de leite volta
    await t.pumpAndSettle();
    await t.ensureVisible(find.text('Ajustar').first);
    await t.pumpAndSettle();
    await t.tap(find.text('Ajustar').first);
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Ajustar ingrediente novo'), findsOneWidget);
    await t.enterText(find.byType(TextField).first, 'Creme de leite caixinha');
    await t.tap(find.text('Usar'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));

    await t.tap(find.textContaining('Lançar no estoque'));
    await t.pumpAndSettle(const Duration(milliseconds: 200));
    final items = ((be.bodies['POST /api/nota/importar'] as Map)['items'] as List).cast<Map>();
    expect(items, hasLength(1));
    expect(items.single['name'], 'Creme de leite caixinha');
  });

  testWidgets('nota já lançada: avisa e não deixa lançar de novo', (t) async {
    await openFlow(t, backend(nota: preview(already: '2026-09-20T10:00:00Z')));
    await t.tap(find.text('simular leitura'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.textContaining('já foi lançada'), findsOneWidget);
    expect(t.widget<FilledButton>(find.widgetWithText(FilledButton, 'Nota já lançada')).onPressed, isNull);
  });

  testWidgets('erro do servidor (ex.: página sem itens) mostra o aviso e continua na câmera', (t) async {
    await openFlow(t, backend(readStatus: 422, readBody: {'error': 'Não consegui ler os itens dessa nota.'}));
    await t.tap(find.text('simular leitura'));
    await t.pump(const Duration(milliseconds: 300));
    expect(find.text('Não consegui ler os itens dessa nota.'), findsOneWidget);
    expect(find.textContaining('Aponte a câmera'), findsOneWidget);
    await t.pump(const Duration(seconds: 6)); // deixa o aviso e o bloqueio do mesmo código passarem
  });

  testWidgets('QR code que não é de nota fiscal é recusado sem nem chamar o servidor', (t) async {
    final be = backend();
    await openFlow(t, be, link: 'https://exemplo.com/promo');
    await t.tap(find.text('simular leitura'));
    await t.pump(const Duration(milliseconds: 300));
    expect(find.textContaining('não é de uma nota fiscal'), findsOneWidget);
    expect(be.calls.contains('POST /api/nota/ler'), isFalse);
    await t.pump(const Duration(seconds: 6));
  });

  testWidgets('falha ao baixar a página da nota (sem internet do portal)', (t) async {
    final be = backend();
    await openFlow(t, be, fetch: (_) async => throw ReceiptException('Não consegui abrir a página da nota. Confira a internet e tente de novo.'));
    await t.tap(find.text('simular leitura'));
    await t.pump(const Duration(milliseconds: 300));
    expect(find.textContaining('Não consegui abrir a página da nota'), findsOneWidget);
    expect(be.calls.contains('POST /api/nota/ler'), isFalse);
    await t.pump(const Duration(seconds: 6));
  });
}
