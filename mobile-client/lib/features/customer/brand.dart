import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

final brandTheme = buildTheme(
  AppColors.brand,
  Brightness.light,
  radius: 18,
  buttonHeight: 52,
);

final brandDarkTheme = buildTheme(
  AppColors.dark,
  Brightness.dark,
  radius: 18,
  buttonHeight: 52,
);

/// Loja e área do cliente: rosa da marca, claro ou escuro conforme o ThemeController
/// do app (RaykookieApp sempre fornece um), padrão = segue o sistema.
class BrandScope extends StatelessWidget {
  const BrandScope({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final mode = context.watch<ThemeController>().mode;
    final dark = mode == ThemeMode.dark || (mode == ThemeMode.system && MediaQuery.platformBrightnessOf(context) == Brightness.dark);
    return Theme(data: dark ? brandDarkTheme : brandTheme, child: child);
  }
}
