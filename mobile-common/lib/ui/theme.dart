import 'package:flutter/material.dart';

/// Cores do app (mesmos tokens do CSS do web). `context.c` devolve as cores do tema atual.
/// Os campos opcionais ([displayFamily], [brandArt], [rosa], [choco]) existem para o app do cliente ter a cara do site
/// (título em serifa, placeholder de marca); o app do dono segue com os padrões e não muda.
@immutable
class AppColors extends ThemeExtension<AppColors> {
  const AppColors({
    required this.bg, required this.surface, required this.surface2, required this.ink, required this.muted, required this.line,
    required this.pink, required this.pinkStrong, required this.pinkTint, required this.onPink, required this.ok, required this.warn, required this.bad,
    this.displayFamily = 'Fraunces', this.brandArt = false, this.rosa = const Color(0xFFE94B8A), this.choco = const Color(0xFF3A1D12),
  });
  final Color bg, surface, surface2, ink, muted, line, pink, pinkStrong, pinkTint, onPink, ok, warn, bad;

  /// Fonte dos títulos (`display()`): Fraunces no app do dono, Young Serif no app do cliente.
  final String displayFamily;

  /// Sem foto, mostra o placeholder da marca (símbolo do cookie sobre um tom do doce) em vez das ilustrações.
  final bool brandArt;

  /// Rosa da marca para preenchimento e decoração (não serve como cor de texto: pouco contraste no creme).
  final Color rosa;

  /// Chocolate: fundo das seções escuras.
  final Color choco;

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

  /// App do cliente, tema claro: a identidade do site (creme, cacau e o rosa da marca). Mesmos valores do `site.css`.
  static const brand = AppColors(
    bg: Color(0xFFFBF3EA), surface: Color(0xFFFFFAF4), surface2: Color(0xFFF4E6D8), ink: Color(0xFF2A160F), muted: Color(0xFF76604F),
    line: Color(0xFFE7D3BF), pink: Color(0xFFD42A76), pinkStrong: Color(0xFFA81857), pinkTint: Color(0xFFFBDDE8), onPink: Color(0xFFFFFAF4),
    ok: Color(0xFF1F8F5F), warn: Color(0xFFA06A12), bad: Color(0xFFC0303F),
    displayFamily: 'YoungSerif', brandArt: true,
  );

  /// App do cliente, tema escuro: o mesmo mundo, à noite (chocolate no lugar do creme).
  static const brandDark = AppColors(
    bg: Color(0xFF1E100A), surface: Color(0xFF2A1710), surface2: Color(0xFF36201A), ink: Color(0xFFFBF3EA), muted: Color(0xFFCDB6A6),
    line: Color(0xFF4A2E22), pink: Color(0xFFFF82B0), pinkStrong: Color(0xFFFF9EC3), pinkTint: Color(0xFF4A1E32), onPink: Color(0xFF2A160F),
    ok: Color(0xFF47C98D), warn: Color(0xFFE3B04B), bad: Color(0xFFFF7482),
    displayFamily: 'YoungSerif', brandArt: true, choco: Color(0xFF120905),
  );
  static const rkRosaClaro = Color(0xFFFBDDE8);
  static const rkEscuro = Color(0xFF3A1D12);

  /// Dourado do "business" (claro → profundo). Sobre fundo claro usa-se o mais fechado, para manter o contraste.
  /// Mesmos valores das logos geradas por `scripts/gerar-logo-business.py` e dos tokens `--gold-*` do CSS.
  static const goldOnDark = [Color(0xFFF7E3A3), Color(0xFFD9A93F), Color(0xFFA87A22)];
  static const goldOnLight = [Color(0xFFC9962B), Color(0xFFA67614), Color(0xFF7A520C)];

  /// Família do "business" (Fraunces itálica, declarada no pubspec do app que a usa).
  static const businessFamily = 'FrauncesItalic';

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

/// Estilo dos títulos. No app do cliente a fonte é a Young Serif (peso único, sem variações); no do dono, a Fraunces.
TextStyle display(BuildContext context, {double size = 28, FontWeight weight = FontWeight.w600, Color? color, double height = 1.1}) {
  final c = context.c;
  final serif = c.displayFamily != displayFamily;
  return TextStyle(
    fontFamily: c.displayFamily, fontSize: size, fontWeight: serif ? FontWeight.w400 : weight, height: height, letterSpacing: serif ? -0.2 : -0.4, color: color ?? c.ink,
    fontVariations: serif ? null : [FontVariation('opsz', size.clamp(9, 144).toDouble()), const FontVariation('SOFT', 50)],
  );
}

/// [radius] é o arredondamento de campos e cartões; [buttonRadius], o dos botões (padrão: igual a [radius]; o app do cliente usa pílula);
/// [buttonHeight], a altura mínima dos botões (a loja usa botões maiores).
ThemeData buildTheme(AppColors c, Brightness brightness, {double radius = 14, double buttonHeight = 48, double? buttonRadius}) {
  final scheme = ColorScheme.fromSeed(seedColor: c.pink, brightness: brightness).copyWith(
    primary: c.pink, onPrimary: c.onPink, secondary: c.pinkStrong, surface: c.surface, onSurface: c.ink, error: c.bad, outline: c.line,
    surfaceContainerHighest: c.surface2,
  );
  final r = BorderRadius.circular(radius);
  final br = BorderRadius.circular(buttonRadius ?? radius);
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
        shape: RoundedRectangleBorder(borderRadius: br), textStyle: const TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700, fontSize: 16),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: c.ink, minimumSize: Size(48, buttonHeight), side: BorderSide(color: c.line, width: 1.5),
        shape: RoundedRectangleBorder(borderRadius: br), textStyle: const TextStyle(fontFamily: _sans, fontWeight: FontWeight.w700, fontSize: 16),
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
