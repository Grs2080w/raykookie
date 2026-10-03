import 'package:flutter/material.dart';

/// Cores do app (mesmos tokens do CSS do web). `context.c` devolve as cores do tema atual.
@immutable
class AppColors extends ThemeExtension<AppColors> {
  const AppColors({
    required this.bg, required this.surface, required this.surface2, required this.ink, required this.muted, required this.line,
    required this.pink, required this.pinkStrong, required this.pinkTint, required this.onPink, required this.ok, required this.warn, required this.bad,
  });
  final Color bg, surface, surface2, ink, muted, line, pink, pinkStrong, pinkTint, onPink, ok, warn, bad;

  /// Painel da confeiteira, tema claro.
  static const light = AppColors(
    bg: Color(0xFFFAF6F8), surface: Color(0xFFFFFFFF), surface2: Color(0xFFF4EEF1), ink: Color(0xFF241A20), muted: Color(0xFF74666D),
    line: Color(0xFFEADFE4), pink: Color(0xFFD42A76), pinkStrong: Color(0xFFB81F63), pinkTint: Color(0xFFFCE8F1), onPink: Color(0xFFFFFFFF),
    ok: Color(0xFF1F8F5F), warn: Color(0xFFB7791F), bad: Color(0xFFD13B4B),
  );

  /// Painel da confeiteira, tema escuro.
  static const dark = AppColors(
    bg: Color(0xFF130E12), surface: Color(0xFF1C151A), surface2: Color(0xFF251C22), ink: Color(0xFFF7EEF3), muted: Color(0xFFAB9CA4),
    line: Color(0xFF322730), pink: Color(0xFFFF5C9D), pinkStrong: Color(0xFFFF7FB1), pinkTint: Color(0xFF3A1A2B), onPink: Color(0xFF2A0A19),
    ok: Color(0xFF47C98D), warn: Color(0xFFE3B04B), bad: Color(0xFFFF7482),
  );

  /// Loja e área do cliente: sempre clara, com o rosa da marca raykookie.
  static const brand = AppColors(
    bg: Color(0xFFFFF8FA), surface: Color(0xFFFFFFFF), surface2: Color(0xFFF8EEF2), ink: Color(0xFF2A1B22), muted: Color(0xFF7A6670),
    line: Color(0xFFF1DEE6), pink: Color(0xFFE94B8A), pinkStrong: Color(0xFFB81F63), pinkTint: Color(0xFFFCE6EF), onPink: Color(0xFF2A1B22),
    ok: Color(0xFF1F8F5F), warn: Color(0xFFB7791F), bad: Color(0xFFD13B4B),
  );
  static const rkRosaClaro = Color(0xFFFCE6EF);
  static const rkEscuro = Color(0xFF2A1B22);

  @override
  AppColors copyWith() => this;
  @override
  AppColors lerp(ThemeExtension<AppColors>? other, double t) => other is AppColors ? (t < 0.5 ? this : other) : this;
}

extension AppColorsX on BuildContext {
  AppColors get c => Theme.of(this).extension<AppColors>()!;
}

const _sans = 'Figtree';
const displayFamily = 'Fraunces';

TextStyle display(BuildContext context, {double size = 28, FontWeight weight = FontWeight.w600, Color? color, double height = 1.1}) => TextStyle(
      fontFamily: displayFamily, fontSize: size, fontWeight: weight, height: height, letterSpacing: -0.4, color: color ?? context.c.ink,
      fontVariations: [FontVariation('opsz', size.clamp(9, 144).toDouble()), const FontVariation('SOFT', 50)],
    );

/// [radius] é o arredondamento de botões e campos; [buttonHeight], a altura mínima dos botões (a loja usa botões maiores).
ThemeData buildTheme(AppColors c, Brightness brightness, {double radius = 14, double buttonHeight = 48}) {
  final scheme = ColorScheme.fromSeed(seedColor: c.pink, brightness: brightness).copyWith(
    primary: c.pink, onPrimary: c.onPink, secondary: c.pinkStrong, surface: c.surface, onSurface: c.ink, error: c.bad, outline: c.line,
    surfaceContainerHighest: c.surface2,
  );
  final r = BorderRadius.circular(radius);
  OutlineInputBorder border(Color col, [double w = 1]) => OutlineInputBorder(borderRadius: r, borderSide: BorderSide(color: col, width: w));
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: c.bg,
    fontFamily: _sans,
    extensions: [c],
    textTheme: Typography.material2021().black.apply(fontFamily: _sans, bodyColor: c.ink, displayColor: c.ink),
    appBarTheme: AppBarTheme(backgroundColor: c.bg, foregroundColor: c.ink, elevation: 0, scrolledUnderElevation: 0, centerTitle: false),
    dividerTheme: DividerThemeData(color: c.line, space: 1, thickness: 1),
    inputDecorationTheme: InputDecorationTheme(
      filled: true, fillColor: c.surface, isDense: false, contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      border: border(c.line), enabledBorder: border(c.line), focusedBorder: border(c.pink, 2), disabledBorder: border(c.line),
      errorBorder: border(c.bad), focusedErrorBorder: border(c.bad, 2),
      hintStyle: TextStyle(color: c.muted.withValues(alpha: 0.7)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: c.pink, foregroundColor: c.onPink, minimumSize: Size(48, buttonHeight),
        shape: RoundedRectangleBorder(borderRadius: r), textStyle: const TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700, fontSize: 16),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.ink, minimumSize: Size(48, buttonHeight), side: BorderSide(color: c.line, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: r), textStyle: const TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700, fontSize: 16),
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: c.pinkStrong, textStyle: const TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700))),
    chipTheme: ChipThemeData(
      backgroundColor: c.surface, selectedColor: c.pinkTint, side: BorderSide(color: c.line), shape: const StadiumBorder(),
      labelStyle: TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700, color: c.ink, fontSize: 13), showCheckmark: false,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: c.surface, indicatorColor: c.pinkTint, surfaceTintColor: Colors.transparent,
      labelTextStyle: WidgetStateProperty.resolveWith((s) => TextStyle(fontFamily: _sans, fontSize: 12, fontWeight: FontWeight.w600, color: s.contains(WidgetState.selected) ? c.pinkStrong : c.muted)),
      iconTheme: WidgetStateProperty.resolveWith((s) => IconThemeData(color: s.contains(WidgetState.selected) ? c.pinkStrong : c.muted)),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: c.surface, surfaceTintColor: Colors.transparent, showDragHandle: true,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
    ),
    dialogTheme: DialogThemeData(backgroundColor: c.surface, surfaceTintColor: Colors.transparent, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24))),
    snackBarTheme: SnackBarThemeData(behavior: SnackBarBehavior.floating, backgroundColor: c.surface, contentTextStyle: TextStyle(color: c.ink, fontWeight: FontWeight.w600, fontFamily: _sans)),
    floatingActionButtonTheme: FloatingActionButtonThemeData(backgroundColor: c.pink, foregroundColor: c.onPink, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18))),
  );
}
