import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/pix/br_code.dart';

/// O QR Code do Pix estático de um produto, pronto para o cliente escanear. O QR é desenhado aqui mesmo, sem rede.
class PixQrScreen extends StatelessWidget {
  const PixQrScreen({super.key, required this.title, required this.amount, required this.config});
  final String title;
  final double amount;
  final PixConfig config;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final payload = buildPixPayload(config, amount);
    return Scaffold(
      appBar: AppBar(title: Text(title, overflow: TextOverflow.ellipsis)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          children: [
            Center(child: Text(money(amount), style: display(context, size: 44, color: c.pinkStrong))),
            const SizedBox(height: 14),
            // fundo branco fixo: o leitor do banco precisa de contraste, inclusive no tema escuro
            Center(
              child: Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20)),
                child: QrImageView(
                  key: ValueKey(payload),
                  data: payload,
                  size: 280,
                  backgroundColor: Colors.white,
                  errorCorrectionLevel: QrErrorCorrectLevel.M,
                  semanticsLabel: 'QR Code Pix de $title, ${money(amount)}',
                ),
              ),
            ),
            const SizedBox(height: 14),
            Center(child: Text('Pix para ${pixText(config.name, 25)}', style: TextStyle(color: c.muted, fontWeight: FontWeight.w600))),
            const SizedBox(height: 18),
            BusyButton(
              label: 'Copiar Pix copia e cola',
              icon: Icons.copy_rounded,
              outlined: true,
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: payload));
                Toast.ok('Pix copia e cola copiado');
              },
            ),
          ],
        ),
      ),
    );
  }
}
