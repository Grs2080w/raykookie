// Desafio 3DS do banco (confirmação do cartão). Não existe SDK do Mercado Pago para Flutter: a página do desafio roda
// num iframe dentro de um WebView em tela cheia, e o aviso de "terminou" (mensagem COMPLETE do iframe) chega ao app
// por um canal JavaScript. Depois do desafio, [withChallenge] pergunta ao servidor (sem cobrar de novo) até o
// pagamento sair de "pendente".
import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Abre o desafio em tela cheia e volta quando o banco avisa que terminou (ou quando o cliente fecha).
Future<void> runThreeDsChallenge(BuildContext context, String url) =>
    Navigator.of(context).push<void>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => ThreeDsChallengePage(url: url)));

/// Página com o iframe do desafio. Só mensagens vindas do Mercado Pago valem.
String threeDsHtml(String url) {
  final src = const HtmlEscape(HtmlEscapeMode.attribute).convert(url);
  return '<!doctype html><html><head><meta charset="utf-8">'
      '<meta name="viewport" content="width=device-width,initial-scale=1">'
      '<style>html,body{margin:0;height:100%;background:#fff}iframe{border:0;width:100%;height:100%}</style></head>'
      '<body><iframe src="$src" title="Confirmação do cartão"></iframe>'
      '<script>window.addEventListener("message",function(e){try{var h=new URL(e.origin).hostname;'
      'if(/(^|\\.)mercadopago\\.com(\\.br)?\$/.test(h)&&e.data&&e.data.status==="COMPLETE"){Challenge.postMessage("COMPLETE");}}catch(_){}});'
      '</script></body></html>';
}

class ThreeDsChallengePage extends StatefulWidget {
  const ThreeDsChallengePage({super.key, required this.url});
  final String url;

  @override
  State<ThreeDsChallengePage> createState() => _ThreeDsChallengePageState();
}

class _ThreeDsChallengePageState extends State<ThreeDsChallengePage> {
  WebViewController? _controller;

  @override
  void initState() {
    super.initState();
    // sem WebView na plataforma (testes, desktop): a página mostra o aviso e o cliente fecha
    if (WebViewPlatform.instance == null) return;
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..addJavaScriptChannel('Challenge', onMessageReceived: (m) {
        if (m.message == 'COMPLETE' && mounted) Navigator.of(context).pop();
      })
      ..loadHtmlString(threeDsHtml(widget.url), baseUrl: 'https://raykookie.com.br');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Confirme no seu banco'),
        leading: IconButton(icon: const Icon(Icons.close), tooltip: 'Fechar', onPressed: () => Navigator.of(context).pop()),
      ),
      body: SafeArea(
        child: Column(children: [
          Expanded(
            child: _controller == null
                ? const Center(child: Padding(padding: EdgeInsets.all(24), child: Text('Não foi possível abrir a confirmação do banco neste aparelho.')))
                : WebViewWidget(controller: _controller!),
          ),
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Text('Seu banco pede esta confirmação para proteger a compra. Ao terminar, esta tela fecha sozinha.', style: TextStyle(fontSize: 12)),
          ),
        ]),
      ),
    );
  }
}

/// Se a resposta do cartão pede o desafio 3DS (`challenge_url`): mostra o desafio, espera o fim e confere o resultado
/// com [status] (um GET que não cobra de novo), algumas vezes, porque o banco pode demorar uns segundos para avisar.
/// Sem desafio, devolve [result] como veio. [run] existe para os testes.
Future<Map<String, dynamic>> withChallenge(
  BuildContext context,
  Map<String, dynamic> result,
  Future<Object?> Function() status, {
  Future<void> Function(BuildContext, String) run = runThreeDsChallenge,
  Duration wait = const Duration(seconds: 2),
  int tries = 8,
}) async {
  final url = result['challenge_url'];
  if (url is! String || url.isEmpty) return result;
  await run(context, url);
  var r = Map<String, dynamic>.from(await status() as Map);
  for (var i = 1; i < tries && r['pending'] == true && r['approved'] != true; i++) {
    await Future<void>.delayed(wait);
    r = Map<String, dynamic>.from(await status() as Map);
  }
  return r;
}
