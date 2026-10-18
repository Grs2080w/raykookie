import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/config.dart';

void main() {
  // config/urls.json é a fonte única dos endereços; os padrões do AppConfig só valem no desenvolvimento
  // e não podem destoar dela (nos builds, o CD e o publish-apps passam os valores do arquivo).
  test('os padrões do AppConfig são os de config/urls.json', () {
    final urls = jsonDecode(File('../config/urls.json').readAsStringSync()) as Map<String, dynamic>;
    expect(AppConfig.apiBase, urls['API_BASE']);
    expect(AppConfig.siteBase, urls['SITE_URL']);
  });
}
