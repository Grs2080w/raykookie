import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';
import 'package:raykookie_gestao/core/pix/br_code.dart';
import 'package:raykookie_gestao/core/pix/venda_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'helpers.dart';

const _cookie = {'id': 'r1', 'name': 'Cookie Red Velvet', 'price': 12.5, 'category': 'Cookies'};
const _combo = {'id': 'combo-1', 'name': 'Combo 4 cookies', 'price': 40.0, 'category': 'Combos', 'combo': {'contents': '4 Cookie', 'full_price': 50.0, 'savings': 10.0}};
final _menu = {
  'items': [_cookie, _combo],
  'groups': [
    {'id': 'cookies', 'name': 'Cookies', 'items': [_cookie]},
    {'id': 'combos', 'name': 'Combos', 'items': [_combo]},
  ],
};
const _cfg = PixConfig(kind: PixKeyKind.cpf, key: '52998224725', name: 'RAYKOOKIE', city: 'SAO PAULO');

/// Abre o app na Venda Presencial como o widget faz: sessão só do aparelho, sem falar com o servidor.
Future<({VendaStore venda, BiometricLock lock, FakeBackend be})> open(
  WidgetTester t, {
  bool withMenu = true,
  bool withConfig = true,
  bool locked = false,
  bool logged = true,
  FakeBiometric? bio,
}) async {
  useBigPhone(t);
  SharedPreferences.setMockInitialValues({
    if (withMenu) 'venda_menu': jsonEncode(_menu),
    if (withConfig) 'venda_pix': jsonEncode(_cfg.toJson()),
  });
  final prefs = await SharedPreferences.getInstance();
  final be = FakeBackend({}); // qualquer chamada de rede responde 404: é como estar sem internet
  final store = MemoryTokenStore();
  if (logged) await store.write('owner_token', 'tok-dono');
  if (locked) await store.write('bio_on:dona', '1');
  await store.write('bio_last_user', 'dona');
  final session = be.session(store: store);
  await session.loadLocal();
  final lock = BiometricLock(session: session, auth: bio ?? FakeBiometric(accepts: false));
  await lock.init();
  final venda = VendaStore(prefs);
  await t.pumpWidget(GestaoApp(session: session, theme: ThemeController(), lock: lock, venda: venda, initialLocation: vendaRoute));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  return (venda: venda, lock: lock, be: be);
}

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  testWidgets('abre com o cardápio já salvo e, sem internet, mostra produtos e combos', (t) async {
    await open(t);
    expect(find.text('Venda presencial'), findsOneWidget);
    expect(find.text('Cookie Red Velvet'), findsOneWidget);
    expect(find.text('Combo 4 cookies'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('tocar no produto abre o QR do Pix estático com o valor dele', (t) async {
    await open(t);
    await t.tap(find.text('Cookie Red Velvet'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    final payload = buildPixPayload(_cfg, 12.5);
    expect(payload, contains('540512.50'));
    expect(find.byKey(ValueKey(payload)), findsOneWidget); // o QR é desenhado com exatamente este "copia e cola"
    expect(find.byType(QrImageView), findsOneWidget);
    expect(find.text('Copiar Pix copia e cola'), findsOneWidget);
  });

  testWidgets('abre sem pedir biometria mesmo com o app trancado; sair da tela volta a trancar', (t) async {
    final bio = FakeBiometric(accepts: false);
    final app = await open(t, locked: true, bio: bio);
    expect(app.lock.locked, isTrue);
    expect(find.text('Desbloquear'), findsNothing);
    expect(find.text('Cookie Red Velvet'), findsOneWidget);
    expect(bio.asked, 0);

    GoRouter.of(t.element(find.text('Venda presencial'))).go('/painel/inicio');
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Desbloquear'), findsOneWidget);
  });

  testWidgets('a abertura não fala com o servidor e funciona até sem login', (t) async {
    final app = await open(t, logged: false);
    expect(find.text('Cookie Red Velvet'), findsOneWidget);
    expect(find.text('Bem-vinda de volta'), findsNothing);
    expect(app.be.calls.where((c) => c != 'GET /api/menu'), isEmpty);
  });

  testWidgets('sem chave Pix: avisa e leva ao cadastro; chave inválida é recusada, válida é salva no aparelho', (t) async {
    final app = await open(t, withConfig: false);
    expect(find.text('Cadastre sua chave Pix para gerar os QR Codes.'), findsOneWidget);
    await t.tap(find.text('Cookie Red Velvet'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Salvar'), findsOneWidget);

    await t.enterText(find.byType(TextField).at(0), '111.111.111-11');
    await t.enterText(find.byType(TextField).at(2), 'São Paulo');
    await t.tap(find.text('Salvar'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Chave inválida para o tipo CPF.'), findsOneWidget);
    expect(app.venda.config, isNull);

    await t.enterText(find.byType(TextField).at(0), '529.982.247-25');
    await t.tap(find.text('Salvar'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(app.venda.config?.key, '52998224725');
    expect(app.venda.config?.city, 'SAO PAULO');
    expect((await SharedPreferences.getInstance()).getString('venda_pix'), isNotNull);
  });

  testWidgets('primeira vez sem cópia do cardápio e sem internet: explica o que fazer', (t) async {
    await open(t, withMenu: false);
    expect(find.textContaining('Abra esta tela uma vez com internet'), findsOneWidget);
  });
}
