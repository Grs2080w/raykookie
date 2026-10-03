import 'package:package_info_plus/package_info_plus.dart';

import 'api_client.dart';

/// Versão instalada (vem do `version:` do pubspec: nome + número do build).
class InstalledVersion {
  const InstalledVersion(this.name, this.build);
  final String name;
  final int build;
  String get label => '$name (build $build)';
}

Future<InstalledVersion> installedVersion() async {
  final i = await PackageInfo.fromPlatform();
  return InstalledVersion(i.version, int.tryParse(i.buildNumber) ?? 0);
}

/// Versão publicada no servidor (downloads/manifest.json).
class Release {
  const Release({required this.version, required this.build, required this.size, required this.date, required this.url});
  final String version, date, url;
  final int build, size;
  String get label => '$version (build $build)';

  factory Release.fromJson(Map<String, dynamic> j) => Release(
        version: '${j['version']}', build: (j['build'] as num?)?.toInt() ?? 0, size: (j['size'] as num?)?.toInt() ?? 0,
        date: '${j['date']}', url: '${j['url'] ?? ''}',
      );
}

/// Última versão publicada de [app] ("client" ou "server"); null se não houver ou sem conexão.
Future<Release?> latestRelease(ApiClient api, String app) async {
  try {
    final m = await api.get('/api/downloads');
    final r = m is Map ? m[app] : null;
    return r is Map<String, dynamic> ? Release.fromJson(r) : null;
  } catch (_) {
    return null;
  }
}

/// Há versão mais nova publicada do que a instalada?
bool isNewer(Release remote, InstalledVersion installed) => remote.build > installed.build;

/// Endereço de download. O app de gestão pede antes um link temporário assinado (o navegador do aparelho não tem o token).
Future<String> downloadUrl(ApiClient api, String app, Release r) async {
  if (app == 'server') {
    final l = await api.post('/api/downloads/server/link') as Map<String, dynamic>;
    return api.absolute('${l['url']}');
  }
  return api.absolute(r.url);
}

String formatSize(int bytes) => bytes >= 1048576 ? '${(bytes / 1048576).toStringAsFixed(1).replaceAll('.', ',')} MB' : '${(bytes / 1024).round()} KB';

/// Só para testes: simula a versão instalada.
void mockInstalledVersion(String version, int build) {
  // ignore: invalid_use_of_visible_for_testing_member
  PackageInfo.setMockInitialValues(appName: 'raykookie', packageName: 'br.com.kntap.raykookie', version: version, buildNumber: '$build', buildSignature: '');
}
