import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie/features/customer/brand.dart';
import 'package:raykookie/features/customer/card_brands.dart';
import 'package:raykookie/features/customer/card_fields.dart';
import 'package:raykookie/features/customer/three_ds_challenge.dart';

void main() {
  group('withChallenge', () {
    testWidgets('sem desafio devolve a resposta como veio, sem consultar nada', (tester) async {
      late BuildContext ctx;
      await tester.pumpWidget(MaterialApp(home: Builder(builder: (c) { ctx = c; return const SizedBox(); })));
      var calls = 0;
      final r = (await tester.runAsync(() => withChallenge(ctx, {'approved': false, 'pending': true}, () async { calls++; return {}; }, run: (context, url) async => fail('não deveria abrir'))))!;
      expect(r, {'approved': false, 'pending': true});
      expect(calls, 0);
    });

    testWidgets('com desafio: abre, espera e consulta até sair de pendente', (tester) async {
      late BuildContext ctx;
      await tester.pumpWidget(MaterialApp(home: Builder(builder: (c) { ctx = c; return const SizedBox(); })));
      String? opened;
      final answers = [
        {'approved': false, 'pending': true, 'challenge_url': 'https://www.mercadopago.com.br/x'},
        {'approved': false, 'pending': true},
        {'approved': true},
      ];
      var i = 0;
      final r = (await tester.runAsync(() => withChallenge(
        ctx,
        {'approved': false, 'pending': true, 'challenge_url': 'https://www.mercadopago.com.br/x'},
        () async => answers[i++],
        run: (_, url) async => opened = url,
        wait: Duration.zero,
      )))!;
      expect(opened, 'https://www.mercadopago.com.br/x');
      expect(r, {'approved': true});
      expect(i, 3);
    });

    testWidgets('desafio não concluído: desiste depois das tentativas e devolve o último estado', (tester) async {
      late BuildContext ctx;
      await tester.pumpWidget(MaterialApp(home: Builder(builder: (c) { ctx = c; return const SizedBox(); })));
      var calls = 0;
      final r = (await tester.runAsync(() => withChallenge(
        ctx,
        {'challenge_url': 'https://www.mercadopago.com.br/x'},
        () async { calls++; return {'approved': false, 'pending': true}; },
        run: (context, url) async {},
        wait: Duration.zero,
        tries: 3,
      )))!;
      expect(r['pending'], true);
      expect(calls, 3);
    });
  });

  group('página do desafio', () {
    test('o endereço entra escapado no atributo e só mensagens do Mercado Pago valem', () {
      final html = threeDsHtml('https://www.mercadopago.com.br/auth?a=1&b="2"');
      expect(html, contains('src="https://www.mercadopago.com.br/auth?a=1&amp;b=&quot;2&quot;"'));
      expect(html, contains('mercadopago'));
      expect(html, contains('COMPLETE'));
      expect(html, contains('Challenge.postMessage'));
    });

    testWidgets('sem WebView na plataforma mostra o aviso e deixa fechar', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: ThreeDsChallengePage(url: 'https://www.mercadopago.com.br/x')));
      expect(find.text('Confirme no seu banco'), findsOneWidget);
      expect(find.textContaining('Não foi possível abrir'), findsOneWidget);
      expect(find.byTooltip('Fechar'), findsOneWidget);
    });
  });

  group('bandeiras aceitas', () {
    test('lê só itens com logo; lixo vira lista vazia', () {
      expect(parseBrands(null), isEmpty);
      expect(parseBrands('x'), isEmpty);
      final list = parseBrands([
        {'id': 'visa', 'name': 'Visa', 'type': 'credit_card', 'logo': 'https://l/visa.png'},
        {'id': 'x', 'name': 'Sem logo', 'type': 'credit_card', 'logo': ''},
        'lixo',
      ]);
      expect(list.map((b) => b.id), ['visa']);
      expect(list.single.type, 'credit_card');
    });

    testWidgets('sem bandeiras não mostra nada (nunca inventa lista)', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: AcceptedBrands(brands: []))));
      expect(find.byType(Container), findsNothing);
      expect(find.text('Crédito'), findsNothing);
    });

    testWidgets('com bandeiras, agrupa por tipo (crédito, débito, pré-pago)', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: brandTheme,
        home: const Scaffold(
          body: AcceptedBrands(brands: [
            CardBrand(id: 'visa', name: 'Visa', type: 'credit_card', logo: 'https://l/visa.png'),
            CardBrand(id: 'debelo', name: 'Elo Débito', type: 'debit_card', logo: 'https://l/elo.png'),
            CardBrand(id: 'visa', name: 'Visa Prepaid', type: 'prepaid_card', logo: 'https://l/visa.png'),
          ]),
        ),
      ));
      expect(find.text('Crédito'), findsOneWidget);
      expect(find.text('Débito'), findsOneWidget);
      expect(find.text('Pré-pago'), findsOneWidget);
    });
  });

  testWidgets('formulário do cartão mostra as bandeiras aceitas no topo', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: brandTheme,
      home: const Scaffold(
        body: SingleChildScrollView(
          child: CardFields(publicKey: 'TEST', amount: 10, brands: [CardBrand(id: 'master', name: 'Mastercard', type: 'credit_card', logo: 'https://l/m.png')]),
        ),
      ),
    ));
    // "Crédito" aparece na faixa de bandeiras e no seletor de função do cartão
    expect(find.text('Crédito'), findsNWidgets(2));
    expect(find.text('Função do cartão'), findsOneWidget);
  });
}
