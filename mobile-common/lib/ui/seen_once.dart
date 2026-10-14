import 'dart:math';

import 'package:flutter/widgets.dart';

/// Chama [onSeen] uma única vez, quando pelo menos metade do [child] aparece na tela (confere ao montar e a cada
/// rolagem da lista em que está). Serve à medição de "item visto" do cardápio.
class SeenOnce extends StatefulWidget {
  const SeenOnce({super.key, required this.onSeen, required this.child});

  final VoidCallback onSeen;
  final Widget child;

  @override
  State<SeenOnce> createState() => _SeenOnceState();
}

class _SeenOnceState extends State<SeenOnce> {
  ScrollPosition? _position;
  bool _done = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _position?.removeListener(_check);
    _position = Scrollable.maybeOf(context)?.position;
    if (!_done) _position?.addListener(_check);
    WidgetsBinding.instance.addPostFrameCallback((_) => _check());
  }

  void _check() {
    if (_done || !mounted) return;
    final box = context.findRenderObject();
    if (box is! RenderBox || !box.attached || !box.hasSize || box.size.height <= 0) return;
    final screen = MediaQuery.sizeOf(context).height;
    final top = box.localToGlobal(Offset.zero).dy;
    final visible = min(top + box.size.height, screen) - max(top, 0.0);
    if (visible >= box.size.height * 0.5) {
      _done = true;
      _position?.removeListener(_check);
      widget.onSeen();
    }
  }

  @override
  void dispose() {
    _position?.removeListener(_check);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
