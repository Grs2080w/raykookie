import 'dart:async';
import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/biometric.dart';
import 'core/pix/venda_store.dart';
import 'core/push.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  Intl.defaultLocale = 'pt_BR';
  final prefs = await SharedPreferences.getInstance();
  final theme = ThemeController(prefs);
  await theme.load();
  final venda = VendaStore(prefs);
  final session = Session(store: const SecureTokenStore());

  // Aberto pelo widget da tela inicial: vai direto para a Venda Presencial, sem esperar rede nem Firebase.
  // A sessão e o push são conferidos depois, em segundo plano; a tela só lê o que está salvo no aparelho.
  if (PlatformDispatcher.instance.defaultRouteName == vendaRoute) {
    await session.loadLocal();
    final lock = BiometricLock(session: session, auth: LocalBiometricAuth());
    await lock.init();
    final push = PushService(session: session, prefs: prefs);
    runApp(GestaoApp(session: session, theme: theme, lock: lock, push: push, venda: venda, initialLocation: vendaRoute));
    unawaited(_warmUp(session, push));
    return;
  }

  // Sem internet, não trava a abertura do app: segue com o que estiver salvo.
  await session.load().timeout(const Duration(seconds: 8), onTimeout: () {});
  final lock = BiometricLock(session: session, auth: LocalBiometricAuth());
  await lock.init(); // já abre trancado quando a biometria está ativada
  final push = PushService(session: session, prefs: prefs);
  await push.init().timeout(
    const Duration(seconds: 8),
    onTimeout: () {},
  ); // sem credenciais do Firebase, segue sem push
  runApp(GestaoApp(session: session, theme: theme, lock: lock, push: push, venda: venda));
}

/// O que a abertura normal faz antes de desenhar, mas depois da primeira tela quando vem do widget.
Future<void> _warmUp(Session session, PushService push) async {
  try {
    await session.load().timeout(const Duration(seconds: 8), onTimeout: () {});
    await push.init().timeout(const Duration(seconds: 8), onTimeout: () {});
  } catch (_) {
    // sem rede ou sem Firebase: o app segue
  }
}
