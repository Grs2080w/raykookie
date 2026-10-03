import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/customer_push.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  Intl.defaultLocale = 'pt_BR';
  final session = Session(store: const SecureTokenStore());
  // Sem internet, não trava a abertura do app: segue com o que estiver salvo.
  await session.load().timeout(const Duration(seconds: 8), onTimeout: () {});
  final push = FirebaseCustomerPush(session: session, prefs: await SharedPreferences.getInstance());
  await push.init().timeout(const Duration(seconds: 8), onTimeout: () {}); // sem Firebase neste build: segue sem avisos
  runApp(RaykookieApp(session: session, push: push));
}
