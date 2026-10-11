import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/app_updates.dart';

void main() {
  final apk = utf8.encode('apk falso');
  Release release({String? hash, int build = 130}) => Release(
        version: '1.0.0', build: build, size: apk.length, date: '', url: '/api/downloads/client',
        sha256: hash ?? sha256.convert(apk).toString(),
      );

  AppUpdates updates({Release? remote, int installed = 125, List<String>? opened, http.Client? client}) => AppUpdates(
        api: ApiClient(base: 'https://teste'),
        app: 'client',
        httpClient: client ?? MockClient((_) async => http.Response.bytes(apk, 200)),
        installed: () async => InstalledVersion('1.0.0', installed),
        latest: () async => remote,
        link: (r) async => 'https://teste${r.url}',
        openInstaller: (path) async {
          opened?.add(path);
          return true;
        },
        openBrowser: (_) async => true,
      );

  test('atualização: só oferece build mais novo que o instalado', () async {
    final same = updates(remote: release(build: 125));
    await same.check();
    expect(same.available, isNull);

    final newer = updates(remote: release());
    await newer.check();
    expect(newer.available?.build, 130);
    expect(newer.offer, isTrue);
  });

  test('atualização: baixa, confere o hash e abre o instalador', () async {
    final opened = <String>[];
    final u = updates(remote: release(), opened: opened);
    await u.check();
    await u.install();
    expect(u.error, isNull);
    expect(u.busy, isFalse);
    expect(opened, hasLength(1));
    expect(await File(opened.single).readAsBytes(), apk);
  });

  test('atualização: download corrompido não abre o instalador', () async {
    final opened = <String>[];
    final u = updates(remote: release(hash: 'outro'), opened: opened);
    await u.check();
    await u.install();
    expect(opened, isEmpty);
    expect(u.error, contains('corrompido'));
  });

  test('atualização: falha no download oferece o navegador', () async {
    final u = updates(remote: release(), client: MockClient((_) async => http.Response('erro', 500)));
    await u.check();
    await u.install();
    expect(u.error, contains('navegador'));
  });

  test('atualização: "Depois" esconde até o aviso de versão nova pedir de novo', () async {
    final u = updates(remote: release());
    await u.check();
    u.dismiss();
    expect(u.offer, isFalse);
    await u.check(force: true);
    expect(u.offer, isTrue);
    expect(AppUpdates.current, same(u));
  });
}
