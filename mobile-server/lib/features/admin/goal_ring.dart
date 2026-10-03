import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'package:raykookie_common/ui/theme.dart';

/// Anel de progresso da meta (igual ao do web).
class GoalRing extends StatelessWidget {
  const GoalRing({super.key, required this.pct, this.size = 150});
  final double pct;
  final double size;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Semantics(
      label: '${pct.round()}% da meta de faturamento',
      child: SizedBox(
        width: size,
        height: size,
        child: Stack(
          alignment: Alignment.center,
          children: [
            TweenAnimationBuilder<double>(
              tween: Tween(begin: 0, end: (pct / 100).clamp(0, 1)),
              duration: const Duration(milliseconds: 1100),
              curve: Curves.easeOutCubic,
              builder: (_, v, _) => CustomPaint(
                size: Size.square(size),
                painter: _RingPainter(v, c.surface2, c.pink),
              ),
            ),
            Text('${pct.round()}%', style: display(context, size: size * 0.26)),
          ],
        ),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  _RingPainter(this.value, this.track, this.color);
  final double value;
  final Color track, color;
  @override
  void paint(Canvas canvas, Size size) {
    final stroke = size.width * 0.085;
    final rect =
        Offset(stroke / 2, stroke / 2) &
        Size(size.width - stroke, size.height - stroke);
    final base = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = track;
    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..color = color;
    canvas.drawArc(rect, 0, math.pi * 2, false, base);
    if (value > 0)
      canvas.drawArc(rect, -math.pi / 2, math.pi * 2 * value, false, arc);
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.value != value || old.color != color || old.track != track;
}
