import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_gestao/core/biometric.dart';

import 'helpers.dart';

FakeBackend backend() => FakeBackend({
      'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
      'POST /api/auth/logout': (_) => (200, {'ok': true}),
      'GET /api/dashboard': (_) => (200, dashboardJson),
      'GET /api/downloads': (_) => (404, {'error': 'sem manifesto'}),
    });

/// Abre o app como quem já estava logado (token guardado), como no primeiro acesso depois da atualização.
Future<({Session session, BiometricLock lock})> open(WidgetTester t, FakeBackend be, FakeBiometric bio, MemoryTokenStore store, {String at = '/painel/inicio'}) async {
  useBigPhone(t);
  final session = be.session(store: store);
  await session.load();
  final lock = BiometricLock(session: session, auth: bio);
  await lock.init();
  await t.pumpWidget(GestaoApp(session: session, theme: ThemeController(), lock: lock, initialLocation: at));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
  return (session: session, lock: lock);
}

MemoryTokenStore logged() => MemoryTokenStore()..write('owner_token', 'tok-dono');

void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  testWidgets('primeiro acesso depois da atualização: pergunta se quer ativar; "Agora não" não pergunta de novo', (t) async {
    final store = logged();
    final bio = FakeBiometric();
    await open(t, backend(), bio, store);
    expect(find.text('Entrar com biometria?'), findsOneWidget);
    await t.tap(find.text('Agora não'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Entrar com biometria?'), findsNothing);
    expect(bio.asked, 0);

    // reabre o app: não pergunta outra vez e não tranca
    await t.pumpWidget(const SizedBox());
    await open(t, backend(), bio, store);
    expect(find.text('Entrar com biometria?'), findsNothing);
    expect(find.text('Desbloquear'), findsNothing);
    expect(find.text('Olá, teste_qa'), findsOneWidget);
  });

  testWidgets('ativar: confirma com a biometria, guarda na conta e o app passa a abrir trancado', (t) async {
    final store = logged();
    final bio = FakeBiometric();
    await open(t, backend(), bio, store);
    await t.tap(find.text('Ativar biometria'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(bio.asked, 1);
    expect(find.text('Entrar com biometria?'), findsNothing);
    expect(await store.read('bio_on:teste_qa'), '1');

    // nova abertura do app: tranca; errar a digital mantém trancado, acertar libera
    await t.pumpWidget(const SizedBox());
    bio.accepts = false;
    await open(t, backend(), bio, store);
    expect(find.text('Desbloquear'), findsOneWidget);
    expect(find.text('Olá, teste_qa'), findsOneWidget); // o painel existe por baixo, mas coberto pela trava
    bio.accepts = true;
    await t.tap(find.text('Desbloquear'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Desbloquear'), findsNothing);
  });

  testWidgets('biometria recusada ao ativar: continua desativada e pode tentar depois', (t) async {
    final store = logged();
    final bio = FakeBiometric(accepts: false);
    await open(t, backend(), bio, store);
    await t.tap(find.text('Ativar biometria'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(await store.read('bio_on:teste_qa'), isNull);
    expect(find.text('Entrar com biometria?'), findsOneWidget); // segue perguntando, pois não respondeu "agora não"
  });

  testWidgets('aparelho sem biometria cadastrada: não pergunta', (t) async {
    await open(t, backend(), FakeBiometric(isAvailable: false), logged());
    expect(find.text('Entrar com biometria?'), findsNothing);
  });

  testWidgets('trancado: "Sair da conta" encerra a sessão e leva ao login', (t) async {
    final store = logged()..write('bio_on:teste_qa', '1');
    final bio = FakeBiometric(accepts: false);
    await open(t, backend(), bio, store);
    expect(find.text('Desbloquear'), findsOneWidget);
    await t.tap(find.text('Sair da conta'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(find.text('Desbloquear'), findsNothing);
    expect(find.text('Bem-vinda de volta'), findsOneWidget);
  });

  testWidgets('sem internet na abertura (nome da conta desconhecido) ainda tranca pela última conta usada', (t) async {
    final store = logged()..write('bio_last_user', 'teste_qa')..write('bio_on:teste_qa', '1');
    final be = FakeBackend({}); // /api/me falha; token continua valendo
    await open(t, be, FakeBiometric(accepts: false), store);
    expect(find.text('Desbloquear'), findsOneWidget);
  });

  testWidgets('volta do segundo plano depois de um tempo: tranca de novo', (t) async {
    final store = logged()..write('bio_on:teste_qa', '1');
    final bio = FakeBiometric();
    final app = await open(t, backend(), bio, store);
    expect(app.lock.locked, isFalse); // a biometria foi aceita na abertura
    app.lock.onPaused();
    app.lock.onResumed();
    expect(app.lock.locked, isFalse); // foi rápido
    final slow = BiometricLock(session: app.session, auth: bio, relockAfter: Duration.zero);
    await slow.init();
    slow.locked = false;
    slow.onPaused();
    slow.onResumed();
    expect(slow.locked, isTrue);
  });

  testWidgets('em Mais dá para ligar e desligar a biometria', (t) async {
    final store = logged();
    final bio = FakeBiometric();
    await open(t, backend(), bio, store, at: '/painel/mais');
    await t.tap(find.text('Agora não'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    await t.tap(find.widgetWithText(SwitchListTile, 'Entrar com biometria'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(await store.read('bio_on:teste_qa'), '1');
    await t.tap(find.widgetWithText(SwitchListTile, 'Entrar com biometria'));
    await t.pumpAndSettle(const Duration(milliseconds: 100));
    expect(await store.read('bio_on:teste_qa'), isNull);
  });
}
