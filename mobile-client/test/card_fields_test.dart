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
}
