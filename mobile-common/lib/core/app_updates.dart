import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:open_filex/open_filex.dart';
import 'package:url_launcher/url_launcher.dart';

import 'api_client.dart';
import 'app_info.dart';

/// Atualização do app pelo próprio app: compara o build publicado (downloads/manifest.json, via /api/downloads) com o
/// instalado, baixa o APK aqui dentro com progresso, confere o sha256 e abre o instalador do Android. A confirmação
/// final é do Android: não dá para instalar sem a pessoa tocar em "Atualizar" lá.
class AppUpdates extends ChangeNotifier {
  AppUpdates({
    required this.api,
    required this.app,
    http.Client? httpClient,
    Future<InstalledVersion> Function()? installed,
    Future<Release?> Function()? latest,
    Future<String> Function(Release)? link,
    Future<bool> Function(String apkPath)? openInstaller,
    Future<bool> Function(String url)? openBrowser,
    DateTime Function()? now,
  })  : _http = httpClient ?? http.Client(),
        _installed = installed ?? installedVersion,
        _latest = latest ?? (() => latestRelease(api, app)),
        _link = link ?? ((r) => downloadUrl(api, app, r)),
        _openInstaller = openInstaller ?? _platformInstall,
        _openBrowser = openBrowser ?? _platformBrowser,
        _now = now ?? DateTime.now {
    current = this;
  }

  /// A instância do app aberto: o aviso "nova versão" (push) pede a conferência por aqui.
  static AppUpdates? current;

  final ApiClient api;
  /// client | server
  final String app;
  final http.Client _http;
  final Future<InstalledVersion> Function() _installed;
  final Future<Release?> Function() _latest;
  final Future<String> Function(Release) _link;
  final Future<bool> Function(String) _openInstaller;
  final Future<bool> Function(String) _openBrowser;
  final DateTime Function() _now;

  /// Versão mais nova que a instalada (null = está em dia ou não deu para conferir).
  Release? available;
  InstalledVersion? installedNow;
  /// 0..1 durante o download; null fora dele.
  double? progress;
  String? error;
  /// "Depois": não mostra a faixa deste build até a próxima abertura do app.
  int? dismissedBuild;
  DateTime? _lastCheck;

  bool get busy => progress != null;
  bool get offer => available != null && available!.build != dismissedBuild;

  /// Confere no máximo 1x por hora, a não ser [force] (aviso de versão nova, toque na faixa).
  Future<void> check({bool force = false}) async {
    final now = _now();
    if (!force && _lastCheck != null && now.difference(_lastCheck!) < const Duration(hours: 1)) return;
    _lastCheck = now;
    final installed = await _installed();
    final remote = await _latest();
    installedNow = installed;
    available = remote != null && isNewer(remote, installed) ? remote : null;
    if (remote == null) _lastCheck = null; // sem rede: tenta de novo na próxima
    if (force) dismissedBuild = null;
    notifyListeners();
  }

  void dismiss() {
    dismissedBuild = available?.build;
    notifyListeners();
  }

  /// Baixa o APK com progresso, confere o hash e abre o instalador.
  Future<void> install() async {
    final r = available;
    if (r == null || busy) return;
    progress = 0;
    error = null;
    notifyListeners();
    final file = File('${Directory.systemTemp.path}/raykookie-$app-${r.build}.apk');
    try {
      final url = await _link(r);
      final resp = await _http.send(http.Request('GET', Uri.parse(url)));
      if (resp.statusCode != 200) throw HttpException('status ${resp.statusCode}');
      final total = resp.contentLength ?? r.size;
      final sink = file.openWrite();
      var got = 0;
      try {
        await for (final chunk in resp.stream) {
          sink.add(chunk);
          got += chunk.length;
          if (total > 0) {
            progress = (got / total).clamp(0, 1).toDouble();
            notifyListeners();
          }
        }
      } finally {
        await sink.close();
      }
      if (r.sha256.isNotEmpty && sha256.convert(await file.readAsBytes()).toString() != r.sha256) {
        throw const FormatException('download corrompido');
      }
      if (!await _openInstaller(file.path)) throw const FileSystemException('instalador não abriu');
      progress = null;
      notifyListeners();
    } catch (e) {
      progress = null;
      error = e is FormatException
          ? 'O download veio corrompido. Tente de novo.'
          : 'Não foi possível atualizar por aqui. Toque em "Baixar pelo navegador".';
      notifyListeners();
    }
  }

  /// Plano B: abre o download no navegador (como era antes).
  Future<void> openInBrowser() async {
    final r = available;
    if (r == null) return;
    try {
      final url = await _link(r); // link novo: o do app de gestão é assinado e vence
      if (!await _openBrowser(url)) error = 'Não foi possível abrir o navegador.';
    } on ApiException catch (e) {
      error = e.message;
    } catch (_) {
      error = 'Não foi possível abrir o navegador.';
    }
    notifyListeners();
  }
}

Future<bool> _platformInstall(String path) async =>
    (await OpenFilex.open(path, type: 'application/vnd.android.package-archive')).type == ResultType.done;

Future<bool> _platformBrowser(String url) => launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);

/// Canal do Android para o aviso de versão nova (o backend manda neste canal).
const updatesChannelId = 'atualizacoes';
const updatesChannelName = 'Atualizações do app';

/// Aviso de push do tipo `update` (versão nova publicada): confere na hora e mostra a faixa de novo.
void onUpdatePush(Map<String, dynamic> data) {
  if (data['type'] == 'update') AppUpdates.current?.check(force: true);
}
