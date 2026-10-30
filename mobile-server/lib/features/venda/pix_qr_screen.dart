import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/pix/br_code.dart';

/// Um item da venda no resumo do QR do carrinho.
typedef PixQrItem = ({String name, int qty, double subtotal});

/// O QR Code do Pix estático de um produto (ou de um carrinho com vários), pronto para o cliente escanear. O QR é
/// desenhado aqui mesmo, sem rede.
class PixQrScreen extends StatelessWidget {
  const PixQrScreen({super.key, required this.title, required this.amount, required this.config, this.items, this.onFinish});
  final String title;
  final double amount;
  final PixConfig config;

  /// Venda do carrinho: o resumo dos itens com quantidades, abaixo do QR.
  final List<PixQrItem>? items;

  /// Venda do carrinho: roda ao tocar em "Venda concluída" (esvazia o carrinho do aparelho; nada vai para o estoque).
  final VoidCallback? onFinish;

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
            if (items != null) ...[
              AppCard(
                padding: const EdgeInsets.all(14),
                child: Column(children: [
                  for (final i in items!)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Row(children: [
                        Expanded(child: Text('${i.qty}× ${i.name}', maxLines: 2, overflow: TextOverflow.ellipsis)),
                        Text(money(i.subtotal), style: const TextStyle(fontWeight: FontWeight.w700)),
                      ]),
                    ),
                  const Divider(height: 18),
                  Row(children: [
                    const Expanded(child: Text('Total', style: TextStyle(fontWeight: FontWeight.w800))),
                    Text(money(amount), style: display(context, size: 20, color: c.pinkStrong)),
                  ]),
                ]),
              ),
              const SizedBox(height: 14),
            ],
            BusyButton(
              label: 'Copiar Pix copia e cola',
              icon: Icons.copy_rounded,
              outlined: true,
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: payload));
                Toast.ok('Pix copia e cola copiado');
              },
            ),
            if (onFinish != null) ...[
              const SizedBox(height: 10),
              BusyButton(
                label: 'Venda concluída · limpar carrinho',
                icon: Icons.check_circle_outline_rounded,
                onPressed: () {
                  onFinish!();
                  Navigator.of(context).pop();
                },
              ),
            ],
          ],
        ),
      ),
    );
  }
}
