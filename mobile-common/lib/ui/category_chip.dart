import 'package:flutter/material.dart';

import 'icons.dart';

/// Etiqueta colorida de categoria (ícone + nome).
class CategoryChip extends StatelessWidget {
  const CategoryChip({super.key, required this.name, required this.icon, required this.color});
  final String name, icon, color;
  @override
  Widget build(BuildContext context) {
    final col = hexColor(color);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
      decoration: BoxDecoration(color: col.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(99)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(categoryIcon(icon), size: 14, color: col),
        const SizedBox(width: 5),
        Flexible(child: Text(name, overflow: TextOverflow.ellipsis, style: TextStyle(color: col, fontSize: 12, fontWeight: FontWeight.w700))),
      ]),
    );
  }
}
