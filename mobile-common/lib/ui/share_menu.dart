import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../core/config.dart';
import 'toast.dart';

/// Endereço da página só com o cardápio (a mesma do site): quem recebe o link vê os itens e, ao pedir, entra na conta e cai no novo pedido.
String menuShareUrl() => '${AppConfig.apiBase.replaceAll(RegExp(r'/$'), '')}/cardapio';

/// Quem de fato abre a folha de compartilhar (os testes trocam por uma versão que falha ou responde na hora).
@visibleForTesting
Future<void> Function(ShareParams params) sharePlatform = (params) => SharePlus.instance.share(params);

/// Abre a folha de compartilhar do celular com o link do cardápio. Sem ela (ou se falhar), copia o link.
Future<void> shareMenuLink() async {
  final url = menuShareUrl();
  try {
    await sharePlatform(ShareParams(text: 'Confira o cardápio da Raykookie: $url', subject: 'Cardápio Raykookie'));
  } catch (_) {
    await Clipboard.setData(ClipboardData(text: url));
    Toast.ok('Link do cardápio copiado');
  }
}

/// Compartilha um texto (ex.: a cópia dos dados do cliente). Sem folha de compartilhar, copia e avisa.
Future<void> shareText(String text, {required String subject, required String copiedMsg}) async {
  try {
    await sharePlatform(ShareParams(text: text, subject: subject));
  } catch (_) {
    await Clipboard.setData(ClipboardData(text: text));
    Toast.ok(copiedMsg);
  }
}

/// Compartilha uma imagem (ex.: o story do cardápio) pela folha de compartilhar do celular.
Future<void> shareImage(Uint8List bytes, {required String name, String? text}) async {
  await sharePlatform(ShareParams(text: text, files: [XFile.fromData(bytes, mimeType: 'image/png', name: name)], fileNameOverrides: [name]));
}

/// Botão "Compartilhar cardápio".
class ShareMenuButton extends StatelessWidget {
  const ShareMenuButton({super.key, this.expand = false});
  final bool expand;
  @override
  Widget build(BuildContext context) {
    final btn = OutlinedButton.icon(
      onPressed: shareMenuLink,
      icon: const Icon(Icons.ios_share_rounded, size: 18),
      label: const Text('Compartilhar cardápio'),
    );
    return expand ? SizedBox(width: double.infinity, child: btn) : btn;
  }
}
