/// Configuração do app. Valores podem ser trocados no build:
///   flutter run --dart-define=API_BASE=http://10.0.2.2:10005
class AppConfig {
  /// Endereço do site (o frontend repassa /api ao backend). No emulador Android, o PC é 10.0.2.2.
  static const apiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://raykookie-api.kntap.com.br');

  /// ID do cliente OAuth "Web" do Google (o mesmo GOOGLE_CLIENT_ID do backend). Vazio = login com Google desativado.
  static const googleServerClientId = String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID');

  /// Antecedência mínima da encomenda (dias) — igual ao backend.
  static const leadDays = 1;

  /// "1 dia de antecedência" / "2 dias de antecedência" — leadDays já com o plural certo.
  static String get leadDaysLabel => "$leadDays ${leadDays == 1 ? 'dia' : 'dias'} de antecedência";
}
