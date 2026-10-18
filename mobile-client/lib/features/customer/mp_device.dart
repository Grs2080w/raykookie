// Device ID do antifraude do Mercado Pago. Sem ele, o antifraude vê um aparelho desconhecido e recusa mais
// (cc_rejected_high_risk). Não existe SDK do Mercado Pago para Flutter: o script de segurança dele roda num WebView
// invisível de 1×1 e devolve MP_DEVICE_SESSION_ID, que vai com a cobrança (header X-meli-session-id no backend).
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Página mínima com o script de segurança do Mercado Pago (o mesmo do checkout web).
const _page = '<!doctype html><html><head><meta charset="utf-8">'
    '<script src="https://www.mercadopago.com/v2/security.js" view="checkout"></script>'
    '</head><body></body></html>';

class MpDeviceId extends StatefulWidget {
  const MpDeviceId({super.key, required this.onDeviceId});

  /// Chamado uma vez, com o device ID (só quando o script responde).
  final ValueChanged<String> onDeviceId;

  @override
  State<MpDeviceId> createState() => _MpDeviceIdState();
}

class _MpDeviceIdState extends State<MpDeviceId> {
  WebViewController? _controller;
  Timer? _poll;
  int _tries = 0;

  @override
  void initState() {
    super.initState();
    // sem WebView na plataforma (testes, desktop): segue sem device ID, a cobrança funciona igual
    if (WebViewPlatform.instance == null) return;
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadHtmlString(_page, baseUrl: 'https://raykookie.com.br');
    _poll = Timer.periodic(const Duration(milliseconds: 500), (_) => _read());
  }

  /// Lê o MP_DEVICE_SESSION_ID; desiste depois de 20 s.
  Future<void> _read() async {
    if (++_tries > 40) {
      _poll?.cancel();
      return;
    }
    try {
      final raw = await _controller!.runJavaScriptReturningResult('window.MP_DEVICE_SESSION_ID || ""');
      // Android devolve a string entre aspas (JSON); iOS, sem
      final id = '$raw'.replaceAll('"', '').trim();
      if (id.isEmpty || id == 'null') return;
      _poll?.cancel();
      if (mounted) widget.onDeviceId(id);
    } catch (_) {
      // página ainda carregando: tenta no próximo ciclo
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) return const SizedBox.shrink();
    // precisa estar na árvore para o Android executar o script; 1×1, transparente e sem toque
    return IgnorePointer(
      child: Opacity(opacity: 0, child: SizedBox(width: 1, height: 1, child: WebViewWidget(controller: controller))),
    );
  }
}
