import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/app_info.dart';

void main() {
  const installed = InstalledVersion('1.0.0', 3);
  Release r(int build) => Release(version: '1.0.0', build: build, size: 1, date: '2026-10-01', url: '/x');

  test('só é nova se o build publicado for maior que o instalado', () {
    expect(isNewer(r(4), installed), isTrue);
    expect(isNewer(r(3), installed), isFalse);
    expect(isNewer(r(2), installed), isFalse);
  });
  test('rótulos de versão', () {
    expect(installed.label, '1.0.0 (build 3)');
    expect(r(4).label, '1.0.0 (build 4)');
  });
  test('tamanho legível', () {
    expect(formatSize(20971520), '20,0 MB');
    expect(formatSize(512000), '500 KB');
  });
  test('Release lê o JSON da API', () {
    final x = Release.fromJson({'version': '1.2.0', 'build': 7, 'size': 1048576, 'date': '2026-10-02', 'url': '/api/downloads/client'});
    expect(x.label, '1.2.0 (build 7)');
    expect(x.size, 1048576);
  });
}
