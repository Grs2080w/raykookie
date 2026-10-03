import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/api_client.dart';
import '../core/app_info.dart';
import 'theme.dart';
import 'toast.dart';
import 'widgets.dart';

/// "raykookie 1.0.0 (build 3)": versão instalada, sempre visível numa tela do app.
class VersionLabel extends StatelessWidget {
  const VersionLabel(this.appName, {super.key, this.textAlign = TextAlign.center});
  final String appName;
  final TextAlign textAlign;
  @override
  Widget build(BuildContext context) => FutureBuilder<InstalledVersion>(
        future: installedVersion(),
        builder: (context, snap) => Text(
          snap.hasData ? '$appName · versão ${snap.data!.label}' : appName,
          textAlign: textAlign, style: TextStyle(color: context.c.muted, fontSize: 12.5),
        ),
      );
}

/// Aviso "Nova versão disponível" com botão de baixar, quando o servidor tem um build mais novo que o instalado.
class UpdateBanner extends StatefulWidget {
  const UpdateBanner({super.key, required this.api, required this.app});
  final ApiClient api;
  final String app; // client | server
  @override
  State<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends State<UpdateBanner> {
  late final Future<(InstalledVersion, Release?)> _check = _load();

  Future<(InstalledVersion, Release?)> _load() async => (await installedVersion(), await latestRelease(widget.api, widget.app));

  bool _busy = false;

  Future<void> _download(Release r) async {
    setState(() => _busy = true);
    try {
      final url = await downloadUrl(widget.api, widget.app, r);
      final ok = await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
      if (!ok) Toast.error('Não foi possível abrir o download. Tente pelo site.');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } catch (_) {
      Toast.error('Não foi possível abrir o download. Tente pelo site.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<(InstalledVersion, Release?)>(
        future: _check,
        builder: (context, snap) {
          final d = snap.data;
          if (d == null || d.$2 == null || !isNewer(d.$2!, d.$1)) return const SizedBox.shrink();
          final (installed, r) = (d.$1, d.$2!);
          final c = context.c;
          return Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(18), border: Border.all(color: c.pink.withValues(alpha: 0.4))),
              child: Row(children: [
                Icon(Icons.system_update_rounded, color: c.pinkStrong),
                const SizedBox(width: 12),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Nova versão disponível', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w800)),
                  Text('${r.label} · você está na ${installed.label}', style: TextStyle(color: c.pinkStrong.withValues(alpha: 0.85), fontSize: 12.5)),
                ])),
                const SizedBox(width: 8),
                BusyButton(label: 'Baixar', expand: false, busy: _busy, onPressed: () => _download(r)),
              ]),
            ),
          );
        },
      );
}
