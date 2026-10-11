import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/app_info.dart';
import '../core/app_updates.dart';
import 'theme.dart';
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

/// Aviso "Nova versão disponível": baixa aqui dentro, com barra de progresso, e abre o instalador do Android.
class UpdateBanner extends StatefulWidget {
  const UpdateBanner({super.key, required this.api, required this.app, this.updates});
  final ApiClient api;
  final String app; // client | server
  /// Para testes; sem ele, usa (ou cria) o do app aberto.
  final AppUpdates? updates;
  @override
  State<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends State<UpdateBanner> {
  // reaproveita o do app aberto (o push confere por ele) se for o mesmo app e a mesma sessão
  late final AppUpdates _u = widget.updates ??
      (AppUpdates.current?.app == widget.app && identical(AppUpdates.current!.api, widget.api)
          ? AppUpdates.current!
          : AppUpdates(api: widget.api, app: widget.app));

  @override
  void initState() {
    super.initState();
    _u.check();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: _u,
        builder: (context, _) {
          final r = _u.available;
          if (r == null || !_u.offer) return const SizedBox.shrink();
          final c = context.c;
          final installed = _u.installedNow;
          final pct = _u.progress;
          return Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Container(
              key: const Key('update_banner'),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(18), border: Border.all(color: c.pink.withValues(alpha: 0.4))),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Icon(Icons.system_update_rounded, color: c.pinkStrong),
                  const SizedBox(width: 12),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Nova versão disponível', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w800)),
                    Text(
                      '${r.label}${installed == null ? '' : ' · você está na ${installed.label}'}${r.size > 0 ? ' · ${formatSize(r.size)}' : ''}',
                      style: TextStyle(color: c.pinkStrong.withValues(alpha: 0.85), fontSize: 12.5),
                    ),
                  ])),
                ]),
                const SizedBox(height: 10),
                if (pct != null) ...[
                  ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(value: pct, minHeight: 8)),
                  const SizedBox(height: 6),
                  Text('Baixando… ${(pct * 100).round()}%', style: TextStyle(color: c.pinkStrong, fontSize: 12.5)),
                ] else ...[
                  if (_u.error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(_u.error!, style: TextStyle(color: c.pinkStrong, fontSize: 12.5, fontWeight: FontWeight.w600)),
                    ),
                  Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                    if (_u.error != null)
                      TextButton(onPressed: _u.openInBrowser, child: const Text('Baixar pelo navegador'))
                    else
                      TextButton(onPressed: _u.dismiss, child: const Text('Depois')),
                    const SizedBox(width: 8),
                    BusyButton(key: const Key('update_install'), label: 'Atualizar', expand: false, busy: false, onPressed: _u.install),
                  ]),
                ],
              ]),
            ),
          );
        },
      );
}
