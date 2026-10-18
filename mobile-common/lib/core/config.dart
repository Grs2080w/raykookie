import 'package:flutter/foundation.dart' show visibleForTesting;

/// Configuração do app. Valores podem ser trocados no build:
///   flutter run --dart-define=API_BASE=http://10.0.2.2:10005
class AppConfig {
  /// Endereço do site (o frontend repassa /api ao backend). No emulador Android, o PC é 10.0.2.2.
  static const apiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://raykookie-api.kntap.com.br');

  /// Endereço público do site (páginas como /cardapio) — pode ser diferente do apiBase (que é só a API).
  static const siteBase = String.fromEnvironment('SITE_BASE', defaultValue: 'https://raykookie.com.br');

  static const _googleServerClientIdEnv = String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID');
  /// Só para teste: sobrepõe googleServerClientId (que normalmente só se define em build, via --dart-define).
  @visibleForTesting
  static String? googleServerClientIdOverride;
  /// ID do cliente OAuth "Web" do Google (o mesmo GOOGLE_CLIENT_ID do backend). Vazio = login com Google desativado.
  static String get googleServerClientId => googleServerClientIdOverride ?? _googleServerClientIdEnv;

  /// Antecedência mínima da encomenda (dias) — igual ao backend.
  static const leadDays = 1;

  /// "1 dia de antecedência" / "2 dias de antecedência" — leadDays já com o plural certo.
  static String get leadDaysLabel => "$leadDays ${leadDays == 1 ? 'dia' : 'dias'} de antecedência";
}
