import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/receipt.dart';
import 'receipt_review.dart';

/// Ler nota fiscal: aponta a câmera para o QR code do cupom, confere os itens e lança no estoque.
class ReceiptScreen extends StatefulWidget {
  const ReceiptScreen({super.key});

  /// Só para testes: troca a câmera e o download da página por versões simuladas.
  @visibleForTesting
  static Widget Function(ValueChanged<String> onCode)? debugScanner;
  @visibleForTesting
  static PageFetcher? debugFetcher;

  @override
  State<ReceiptScreen> createState() => _ReceiptScreenState();
}

class _ReceiptScreenState extends State<ReceiptScreen> {
  ReceiptPreview? _preview;
  bool _loading = false;
  String _ignored = ''; // último código que falhou: a câmera continua vendo o mesmo QR, então não repete o erro

  ReceiptService get _service => ReceiptService(
    context.read<Session>().ownerApi,
    fetch: ReceiptScreen.debugFetcher,
  );

  Future<void> _onCode(String code) async {
    if (_loading || code == _ignored) return;
    setState(() => _loading = true);
    try {
      final p = await _service.read(code);
      if (mounted) setState(() => _preview = p);
    } on ReceiptException catch (e) {
      _fail(code, e.message);
    } on ApiException catch (e) {
      _fail(code, e.message);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _fail(String code, String message) {
    _ignored = code;
    Future.delayed(const Duration(seconds: 5), () => _ignored = '');
    Toast.error(message);
  }

  Future<void> _paste() async {
    final ctrl = TextEditingController();
    final clip = (await Clipboard.getData(Clipboard.kTextPlain))?.text ?? '';
    if (clip.startsWith('http')) ctrl.text = clip;
    if (!mounted) return;
    final link = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Colar o link da nota', style: display(ctx, size: 22)),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          maxLines: 3,
          minLines: 1,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            hintText: 'https://www.nfce.fazenda...',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, ctrl.text),
            child: const Text('Ler nota'),
          ),
        ],
      ),
    );
    ctrl.dispose();
    if (link != null && link.trim().isNotEmpty) await _onCode(link);
  }

  @override
  Widget build(BuildContext context) {
    final preview = _preview;
    return Scaffold(
      appBar: AppBar(
        title: Text(preview == null ? 'Ler nota fiscal' : 'Conferir nota'),
      ),
      body: preview != null
          ? ReceiptReview(
              preview: preview,
              service: _service,
              onDone: () => context.pop(true),
            )
          : _ScanBody(loading: _loading, onCode: _onCode, onPaste: _paste),
    );
  }
}

class _ScanBody extends StatelessWidget {
  const _ScanBody({
    required this.loading,
    required this.onCode,
    required this.onPaste,
  });
  final bool loading;
  final ValueChanged<String> onCode;
  final VoidCallback onPaste;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
        child: Column(
          children: [
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(28),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    ColoredBox(
                      color: Colors.black,
                      child:
                          ReceiptScreen.debugScanner?.call(onCode) ??
                          _Camera(onCode: onCode, onPaste: onPaste),
                    ),
                    IgnorePointer(
                      child: Center(
                        child: Container(
                          width: 230,
                          height: 230,
                          decoration: BoxDecoration(
                            border: Border.all(color: Colors.white70, width: 3),
                            borderRadius: BorderRadius.circular(28),
                          ),
                        ),
                      ),
                    ),
                    if (loading)
                      Container(
                        color: Colors.black54,
                        alignment: Alignment.center,
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const CircularProgressIndicator(
                              color: Colors.white,
                            ),
                            const SizedBox(height: 14),
                            Text(
                              'Lendo a nota…',
                              style: display(
                                context,
                                size: 22,
                                color: Colors.white,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),
            Text(
              'Aponte a câmera para o QR code do cupom fiscal (NFC-e). Os itens entram no estoque depois que você conferir.',
              textAlign: TextAlign.center,
              style: TextStyle(color: c.muted, height: 1.4),
            ),
            const SizedBox(height: 12),
            BusyButton(
              label: 'Colar o link da nota',
              icon: Icons.link_rounded,
              outlined: true,
              onPressed: loading ? null : onPaste,
            ),
          ],
        ),
      ),
    );
  }
}

class _Camera extends StatefulWidget {
  const _Camera({required this.onCode, required this.onPaste});
  final ValueChanged<String> onCode;
  final VoidCallback onPaste;
  @override
  State<_Camera> createState() => _CameraState();
}

class _CameraState extends State<_Camera> {
  final _controller = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    detectionSpeed: DetectionSpeed.noDuplicates,
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      MobileScanner(
        controller: _controller,
        onDetect: (capture) {
          for (final b in capture.barcodes) {
            final v = b.rawValue;
            if (v != null && v.isNotEmpty) return widget.onCode(v);
          }
        },
        errorBuilder: (context, error) {
          final denied =
              error.errorCode == MobileScannerErrorCode.permissionDenied;
          return Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Icon(
                  Icons.no_photography_outlined,
                  color: Colors.white70,
                  size: 44,
                ),
                const SizedBox(height: 12),
                Text(
                  denied
                      ? 'O app não tem permissão para usar a câmera. Libere em Configurações do celular > Apps > raykookie gestão > Permissões.'
                      : 'Não foi possível abrir a câmera neste aparelho.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white),
                ),
                const SizedBox(height: 14),
                const Text(
                  'Você ainda pode colar o link da nota.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white70),
                ),
              ],
            ),
          );
        },
      ),
      Positioned(
        right: 10,
        top: 10,
        child: IconButton.filled(
          style: IconButton.styleFrom(
            backgroundColor: Colors.black45,
            foregroundColor: Colors.white,
          ),
          tooltip: 'Lanterna',
          icon: const Icon(Icons.flashlight_on_outlined),
          onPressed: () => _controller.toggleTorch(),
        ),
      ),
    ],
  );
}
