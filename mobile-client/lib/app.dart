import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/toast.dart';

import 'core/customer_push.dart';
import 'features/customer/brand.dart';
import 'router.dart';

class RaykookieApp extends StatefulWidget {
  const RaykookieApp({super.key, required this.session, this.initialLocation = '/', this.push, this.theme});
  final Session session;
  final CustomerPush? push; // avisos do pedido; nulo nos testes = sem avisos
  final String initialLocation;
  final ThemeController? theme; // nulo (ex.: testes) = um controller novo, sem persistência
  @override
  State<RaykookieApp> createState() => _RaykookieAppState();
}

class _RaykookieAppState extends State<RaykookieApp> {
  late final GoRouter _router = buildRouter(widget.session, initialLocation: widget.initialLocation);
  late final CustomerPush _push = widget.push ?? CustomerPush();
  late final ThemeController _theme = widget.theme ?? ThemeController();

  @override
  void initState() {
    super.initState();
    _push.onOpen = _router.go; // tocar no aviso abre Meus pedidos, no pedido certo
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
          ChangeNotifierProvider<CustomerPush>.value(value: _push),
          ChangeNotifierProvider.value(value: _theme),
        ],
        child: Consumer<ThemeController>(
          builder: (context, theme, _) => MaterialApp.router(
            title: 'Raykookie',
            debugShowCheckedModeBanner: false,
            routerConfig: _router,
            scaffoldMessengerKey: messengerKey,
            theme: brandTheme,
            darkTheme: brandDarkTheme,
            themeMode: theme.mode,
            locale: const Locale('pt', 'BR'),
            supportedLocales: const [Locale('pt', 'BR')],
            localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
          ),
        ),
      );
}
