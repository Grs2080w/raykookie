import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/biometric.dart';
import 'core/push.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  Intl.defaultLocale = 'pt_BR';
  final prefs = await SharedPreferences.getInstance();
  final theme = ThemeController(prefs);
  await theme.load();
  final session = Session(store: const SecureTokenStore());
  // Sem internet, não trava a abertura do app: segue com o que estiver salvo.
  await session.load().timeout(const Duration(seconds: 8), onTimeout: () {});
  final lock = BiometricLock(session: session, auth: LocalBiometricAuth());
  await lock.init(); // já abre trancado quando a biometria está ativada
  final push = PushService(session: session, prefs: prefs);
  await push.init().timeout(
    const Duration(seconds: 8),
    onTimeout: () {},
  ); // sem credenciais do Firebase, segue sem push
  runApp(GestaoApp(session: session, theme: theme, lock: lock, push: push));
}
