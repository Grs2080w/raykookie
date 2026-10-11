import 'package:flutter/material.dart';

import '../core/format.dart';
import '../core/models.dart';
import 'theme.dart';

/// O que vai embaixo do nome no cardápio: o conteúdo do combo (com quanto economiza) e os ingredientes, quando a
/// confeiteira escolheu mostrar.
class MenuItemExtras extends StatelessWidget {
  const MenuItemExtras(this.item, {super.key, this.fontSize = 12.5});
  final MenuItem item;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final combo = item.combo;
    if (combo == null && item.ingredients.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 3),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (combo != null) ...[
          Text(combo.contents, style: TextStyle(color: c.ink, fontSize: fontSize, fontWeight: FontWeight.w600)),
          if (combo.savings > 0)
            Text(
              'Economize ${money(combo.savings)} (avulso sai ${money(combo.fullPrice)})',
              style: TextStyle(color: c.pinkStrong, fontSize: fontSize, fontWeight: FontWeight.w700),
            ),
        ],
        if (item.ingredients.isNotEmpty)
          Text(
            'Ingredientes: ${item.ingredients.join(', ')}',
            style: TextStyle(color: c.muted, fontSize: fontSize),
          ),
      ]),
    );
  }
}
