import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';

import 'core/biometric.dart';
import 'core/biometric_gate.dart';
import 'core/push.dart';
import 'router.dart';

class GestaoApp extends StatefulWidget {
  const GestaoApp({
    super.key,
    required this.session,
    required this.theme,
    required this.lock,
    this.push,
    this.initialLocation = '/painel/inicio',
  });
  final Session session;
  final BiometricLock lock;
  final ThemeController theme;
  final PushService? push;
  final String initialLocation;
  @override
  State<GestaoApp> createState() => _GestaoAppState();
}

class _GestaoAppState extends State<GestaoApp> {
  late final GoRouter _router = buildRouter(
    widget.session,
    initialLocation: widget.initialLocation,
  );
  late final PushService _push =
      widget.push ?? PushService(session: widget.session);

  @override
  void initState() {
    super.initState();
    // tocar na notificação: Pedidos vira a base e a tela do aviso (devolução, privacidade...) abre por cima, com "voltar"
    _push.onOpen = (route) {
      if (route == PushService.ordersRoute || route == '/painel/inicio') return _router.go(route);
      _router.go(PushService.ordersRoute);
      _router.push(route);
    };
  }

  @override
  void dispose() {
    _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MultiProvider(
    providers: [
      ChangeNotifierProvider.value(value: widget.session),
      ChangeNotifierProvider.value(value: widget.theme),
      ChangeNotifierProvider.value(value: widget.lock),
      ChangeNotifierProvider.value(value: _push),
    ],
    child: Consumer<ThemeController>(
      builder: (context, theme, _) => MaterialApp.router(
        title: 'Raykookie gestão',
        debugShowCheckedModeBanner: false,
        routerConfig: _router,
        builder: (context, child) =>
            BiometricGate(child: child ?? const SizedBox.shrink()),
        scaffoldMessengerKey: messengerKey,
        theme: buildTheme(AppColors.light, Brightness.light, radius: 16, buttonHeight: 52),
        darkTheme: buildTheme(AppColors.dark, Brightness.dark, radius: 16, buttonHeight: 52),
        themeMode: theme.mode,
        locale: const Locale('pt', 'BR'),
        supportedLocales: const [Locale('pt', 'BR')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
      ),
    ),
  );
}
