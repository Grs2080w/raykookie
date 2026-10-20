import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/ui/theme.dart';

/// Endereço que o QR Code abre: o site da casa (raykookie.com.br).
String get siteQrUrl => AppConfig.siteBase;

/// Botão redondo de QR Code, no canto da tela, que abre a [showSiteQr].
class SiteQrButton extends StatelessWidget {
  const SiteQrButton({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return FloatingActionButton(
      heroTag: 'site-qr',
      tooltip: 'QR Code do site',
      backgroundColor: c.pink,
      foregroundColor: c.onPink,
      elevation: 3,
      shape: const CircleBorder(),
      onPressed: () {
        HapticFeedback.selectionClick();
        showSiteQr(context);
      },
      child: const Icon(Icons.qr_code_2_rounded, size: 30),
    );
  }
}

/// Abre a tela do QR Code com animação: o fundo escurece, o cartão sobe com um leve
/// "pulo" e o QR aparece logo depois. Fechar (toque fora, X ou voltar) faz o caminho inverso.
Future<void> showSiteQr(BuildContext context) {
  final theme = Theme.of(context);
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'Fechar QR Code',
    barrierColor: Colors.black.withValues(alpha: 0.55),
    transitionDuration: const Duration(milliseconds: 520),
    transitionBuilder: (context, anim, secondary, child) {
      final enter = CurvedAnimation(parent: anim, curve: Curves.easeOutBack, reverseCurve: Curves.easeInCubic);
      return FadeTransition(
        opacity: CurvedAnimation(parent: anim, curve: Curves.easeOut, reverseCurve: Curves.easeIn),
        child: SlideTransition(
          position: Tween(begin: const Offset(0, 0.18), end: Offset.zero).animate(enter),
          child: ScaleTransition(scale: Tween(begin: 0.86, end: 1.0).animate(enter), child: child),
        ),
      );
    },
    pageBuilder: (context, anim, secondary) => Theme(data: theme, child: _SiteQrSheet(animation: anim)),
  );
}

/// Cartão central: apresentação, QR Code e endereço do site.
class _SiteQrSheet extends StatelessWidget {
  const _SiteQrSheet({required this.animation});
  final Animation<double> animation;

  /// Entrada escalonada de um item do cartão, entre [from] e [to] da animação (0..1).
  Animation<double> _stagger(double from, double to) =>
      CurvedAnimation(parent: animation, curve: Interval(from, to, curve: Curves.easeOutCubic));

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final host = Uri.parse(siteQrUrl).host;
    return SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Material(
            color: c.bg,
            elevation: 12,
            borderRadius: BorderRadius.circular(28),
            clipBehavior: Clip.antiAlias,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 380),
              child: SingleChildScrollView(
                child: Stack(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(24, 36, 24, 28),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _Reveal(
                            animation: _stagger(0.15, 0.6),
                            child: Column(
                              children: [
                                Text('Leve a Raykookie com você', textAlign: TextAlign.center, style: display(context, size: 28)),
                                const SizedBox(height: 10),
                                Text(
                                  'Aponte a câmera do celular para o código e abra o nosso site, com o cardápio feito à mão.',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(color: c.muted, fontSize: 15, height: 1.4),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(height: 22),
                          ScaleTransition(
                            scale: Tween(begin: 0.7, end: 1.0).animate(
                              CurvedAnimation(parent: animation, curve: const Interval(0.3, 0.95, curve: Curves.easeOutBack)),
                            ),
                            child: FadeTransition(
                              opacity: _stagger(0.35, 0.9),
                              child: Semantics(
                                label: 'QR Code que abre $host',
                                child: Container(
                                  padding: const EdgeInsets.all(16),
                                  decoration: BoxDecoration(
                                    // fundo branco fixo: o QR precisa de contraste também no tema escuro
                                    color: Colors.white,
                                    borderRadius: BorderRadius.circular(24),
                                    border: Border.all(color: c.pink.withValues(alpha: 0.5), width: 2),
                                    boxShadow: [BoxShadow(color: c.pink.withValues(alpha: 0.25), blurRadius: 28, offset: const Offset(0, 10))],
                                  ),
                                  child: QrImageView(
                                    data: siteQrUrl,
                                    size: 220,
                                    errorCorrectionLevel: QrErrorCorrectLevel.M,
                                    eyeStyle: const QrEyeStyle(eyeShape: QrEyeShape.circle, color: Colors.black),
                                    dataModuleStyle: const QrDataModuleStyle(dataModuleShape: QrDataModuleShape.circle, color: Colors.black),
                                  ),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(height: 20),
                          _Reveal(
                            animation: _stagger(0.55, 1.0),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                              decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(99)),
                              child: Text(host, style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700, fontSize: 16)),
                            ),
                          ),
                        ],
                      ),
                    ),
                    Positioned(
                      right: 8,
                      top: 8,
                      child: IconButton(
                        tooltip: 'Fechar',
                        icon: const Icon(Icons.close_rounded),
                        color: c.muted,
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Aparece subindo alguns pixels enquanto sai da transparência.
class _Reveal extends StatelessWidget {
  const _Reveal({required this.animation, required this.child});
  final Animation<double> animation;
  final Widget child;

  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: animation,
    child: SlideTransition(position: Tween(begin: const Offset(0, 0.25), end: Offset.zero).animate(animation), child: child),
  );
}
