import 'package:flutter/material.dart';

import 'theme.dart';

/// Avisos amigáveis (substituem o toast do web). Precisa do [messengerKey] no MaterialApp.
final messengerKey = GlobalKey<ScaffoldMessengerState>();

enum ToastKind { ok, error, warn }

class Toast {
  static String _lastWarn = '';
  static DateTime _lastWarnAt = DateTime.fromMillisecondsSinceEpoch(0);

  static void ok(String msg) => _show(msg, ToastKind.ok);
  static void error(String msg) => _show(msg, ToastKind.error);

  /// Aviso leve (ex.: caractere não permitido). Não repete a mesma mensagem a cada tecla.
  static void warn(String msg) {
    final now = DateTime.now();
    if (msg == _lastWarn && now.difference(_lastWarnAt).inMilliseconds < 2500) return;
    _lastWarn = msg;
    _lastWarnAt = now;
    _show(msg, ToastKind.warn);
  }

  static void _show(String msg, ToastKind kind) {
    final m = messengerKey.currentState;
    if (m == null) return;
    final ctx = m.context;
    final c = Theme.of(ctx).extension<AppColors>()!;
    final (icon, color) = switch (kind) {
      ToastKind.ok => (Icons.check_circle_rounded, c.ok),
      ToastKind.error => (Icons.error_rounded, c.bad),
      ToastKind.warn => (Icons.warning_amber_rounded, c.warn),
    };
    m.hideCurrentSnackBar();
    m.showSnackBar(SnackBar(
      duration: Duration(milliseconds: kind == ToastKind.error ? 6000 : kind == ToastKind.warn ? 3800 : 3200),
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: c.line)),
      content: Row(children: [Icon(icon, color: color, size: 22), const SizedBox(width: 10), Expanded(child: Text(msg))]),
    ));
  }
}
