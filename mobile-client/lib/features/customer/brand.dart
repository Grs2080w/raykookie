import 'package:flutter/material.dart';

import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/widgets.dart';

final brandTheme = buildTheme(
  AppColors.brand,
  Brightness.light,
  radius: 18,
  buttonHeight: 52,
);

/// Loja e área do cliente: sempre no tema claro com o rosa da marca (como o web).
class BrandScope extends StatelessWidget {
  const BrandScope({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Theme(data: brandTheme, child: child);
}

/// Barra de topo da loja: logo compacta e ações.
class BrandAppBar extends StatelessWidget implements PreferredSizeWidget {
  const BrandAppBar({
    super.key,
    this.actions = const [],
    this.onLogoLongPress,
    this.showBack = false,
    this.title,
  });
  final List<Widget> actions;
  final VoidCallback? onLogoLongPress;
  final bool showBack;

  /// Título no lugar da logo (por exemplo, "Novo pedido").
  final String? title;
  @override
  Size get preferredSize => const Size.fromHeight(60);

  @override
  Widget build(BuildContext context) => AppBar(
    backgroundColor: AppColors.rkRosaClaro.withValues(alpha: 0.96),
    automaticallyImplyLeading: showBack,
    toolbarHeight: 60,
    titleSpacing: showBack ? 0 : 16,
    title: title != null
        ? Text(
            title!,
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
          )
        : GestureDetector(
            onLongPress: onLogoLongPress,
            behavior: HitTestBehavior.opaque,
            child: const Padding(
              padding: EdgeInsets.symmetric(vertical: 6),
              child: RkLogo(variant: 'compacta', height: 40),
            ),
          ),
    actions: [...actions, const SizedBox(width: 8)],
    shape: Border(bottom: BorderSide(color: context.c.line)),
  );
}

/// Limita a largura em telas grandes (tablet/web) e centraliza.
class Centered extends StatelessWidget {
  const Centered({super.key, required this.child, this.maxWidth = 720});
  final Widget child;
  final double maxWidth;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: child,
    ),
  );
}
