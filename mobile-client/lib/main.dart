import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:raykookie_common/core/analytics.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/customer_push.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  Intl.defaultLocale = 'pt_BR';
  final prefs = await SharedPreferences.getInstance();
  // medição anônima de uso (desligada, a menos que o build use --dart-define=ENGAGEMENT_ENABLED=true)
  final version = await installedVersion().then<String?>((v) => v.name).catchError((_) => null);
  Analytics.instance.configure(
    api: ApiClient(),
    prefs: prefs,
    platform: defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android',
    appVersion: version ?? '',
  );
  final theme = ThemeController(prefs);
  await theme.load();
  final session = Session(store: const SecureTokenStore());
  // Sem internet, não trava a abertura do app: segue com o que estiver salvo.
  await session.load().timeout(const Duration(seconds: 8), onTimeout: () {});
  final push = FirebaseCustomerPush(session: session, prefs: prefs);
  await push.init().timeout(const Duration(seconds: 8), onTimeout: () {}); // sem Firebase neste build: segue sem avisos
  runApp(RaykookieApp(session: session, theme: theme, push: push));
}
