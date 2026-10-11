import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie/features/customer/brand.dart';
import 'package:raykookie/features/customer/card_fields.dart';

void main() {
  Future<void> pump(WidgetTester tester) => tester.pumpWidget(MaterialApp(
        theme: brandTheme,
        home: const Scaffold(body: SingleChildScrollView(child: CardFields(publicKey: 'TEST', amount: 10))),
      ));

  SegmentedButton<String> selector(WidgetTester tester) => tester.widget<SegmentedButton<String>>(find.byType(SegmentedButton<String>));

  testWidgets('cartão: começa no crédito e troca para débito', (tester) async {
    await pump(tester);
    expect(find.text('Função do cartão'), findsOneWidget);
    expect(selector(tester).selected, {'credit_card'});

    await tester.tap(find.text('Débito'));
    await tester.pump();
    expect(selector(tester).selected, {'debit_card'});
  });

  testWidgets('cartão: sem número digitado, não avisa que a função não bate', (tester) async {
    await pump(tester);
    await tester.tap(find.text('Débito'));
    await tester.pump();
    expect(find.textContaining('só passa no crédito'), findsNothing);
  });

  test('tokenização recusada: mensagem pelo código da causa, genérica sem código conhecido', () {
    expect(cardTokenError({'cause': [{'code': 'E302'}]}), 'Confira o código de segurança (CVV).');
    expect(cardTokenError({'cause': [{'code': 325}]}), 'Confira o mês da validade.');
    expect(cardTokenError({'cause': [{'code': '999'}, {'code': '214'}]}), 'Confira o CPF.');
    expect(cardTokenError({'message': 'bad request'}), 'Confira os dados do cartão e tente de novo.');
  });

  test('função do cartão: pré-pago (cartão de saldo do Mercado Pago, BIN 4174 01) passa como débito', () {
    expect(cardTypeMismatch('debit_card', 'prepaid_card'), isNull);
    expect(cardTypeMismatch('debit_card', 'debit_card'), isNull);
    expect(cardTypeMismatch('credit_card', 'credit_card'), isNull);
    expect(cardTypeMismatch('credit_card', ''), isNull);
    expect(cardTypeMismatch('credit_card', 'prepaid_card'), 'Este cartão usa o saldo da conta (débito). Escolha Débito.');
    expect(cardTypeMismatch('credit_card', 'debit_card'), 'Este é um cartão de débito. Escolha Débito.');
    expect(cardTypeMismatch('debit_card', 'credit_card'), contains('só passa no crédito'));
  });
}
