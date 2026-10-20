import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:raykookie/features/customer/brand.dart';
import 'package:raykookie/features/customer/site_qr_screen.dart';

void main() {
  testWidgets('botão abre a tela com o QR do site e fecha pelo X', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: brandTheme,
        home: const Scaffold(floatingActionButton: SiteQrButton()),
      ),
    );

    expect(find.byType(QrImageView), findsNothing);
    await tester.tap(find.byIcon(Icons.qr_code_2_rounded));
    await tester.pumpAndSettle();

    expect(siteQrUrl, 'https://raykookie.com.br');
    expect(find.byType(QrImageView), findsOneWidget);
    expect(find.text('raykookie.com.br'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.close_rounded));
    await tester.pumpAndSettle();
    expect(find.byType(QrImageView), findsNothing);
  });
}
