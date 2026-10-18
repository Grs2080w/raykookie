// Pix estático (BR Code / EMV do Banco Central), montado 100% no aparelho: sem rede e sem Mercado Pago.

/// Tipo da chave Pix. A confeiteira escolhe o tipo, assim um CPF de 11 dígitos não se confunde com um celular.
enum PixKeyKind {
  cpf('CPF'),
  cnpj('CNPJ'),
  phone('Celular'),
  email('E-mail'),
  random('Aleatória');

  const PixKeyKind(this.label);
  final String label;
}

/// A chave Pix fixa de quem recebe, mais o nome e a cidade que o BR Code exige.
class PixConfig {
  const PixConfig({required this.kind, required this.key, required this.name, required this.city});
  final PixKeyKind kind;
  final String key;
  final String name, city;

  Map<String, String> toJson() => {'kind': kind.name, 'key': key, 'name': name, 'city': city};

  static PixConfig? fromJson(Map<String, dynamic> j) {
    final kind = PixKeyKind.values.where((k) => k.name == j['kind']).firstOrNull;
    if (kind == null || j['key'] is! String || j['name'] is! String || j['city'] is! String) return null;
    return PixConfig(kind: kind, key: j['key'] as String, name: j['name'] as String, city: j['city'] as String);
  }
}

String _digits(String s) => s.replaceAll(RegExp(r'\D'), '');

/// Chave no formato que o BR Code espera (celular com +55, CPF/CNPJ só dígitos, e-mail minúsculo) ou `null` se inválida.
String? normalizePixKey(PixKeyKind kind, String raw) {
  final v = raw.trim();
  switch (kind) {
    case PixKeyKind.cpf:
      final d = _digits(v);
      return d.length == 11 && _cpfOk(d) ? d : null;
    case PixKeyKind.cnpj:
      final d = _digits(v);
      return d.length == 14 && _cnpjOk(d) ? d : null;
    case PixKeyKind.phone:
      var d = _digits(v);
      if (d.length >= 12 && d.startsWith('55')) d = d.substring(2);
      return d.length == 10 || d.length == 11 ? '+55$d' : null;
    case PixKeyKind.email:
      final e = v.toLowerCase();
      return RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(e) && e.length <= 77 ? e : null;
    case PixKeyKind.random:
      final r = v.toLowerCase();
      return RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$').hasMatch(r) ? r : null;
  }
}

bool _cpfOk(String d) {
  if (RegExp(r'^(\d)\1+$').hasMatch(d)) return false;
  int dv(int n) {
    var s = 0;
    for (var i = 0; i < n; i++) {
      s += int.parse(d[i]) * (n + 1 - i);
    }
    final r = (s * 10) % 11;
    return r == 10 ? 0 : r;
  }

  return dv(9) == int.parse(d[9]) && dv(10) == int.parse(d[10]);
}

bool _cnpjOk(String d) {
  if (RegExp(r'^(\d)\1+$').hasMatch(d)) return false;
  int dv(int n) {
    final w = n == 12 ? [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2] : [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
    var s = 0;
    for (var i = 0; i < n; i++) {
      s += int.parse(d[i]) * w[i];
    }
    final r = s % 11;
    return r < 2 ? 0 : 11 - r;
  }

  return dv(12) == int.parse(d[12]) && dv(13) == int.parse(d[13]);
}

const _accents = {
  'á': 'a', 'à': 'a', 'â': 'a', 'ã': 'a', 'ä': 'a', 'é': 'e', 'è': 'e', 'ê': 'e', 'ë': 'e', 'í': 'i', 'ì': 'i', 'î': 'i', 'ï': 'i',
  'ó': 'o', 'ò': 'o', 'ô': 'o', 'õ': 'o', 'ö': 'o', 'ú': 'u', 'ù': 'u', 'û': 'u', 'ü': 'u', 'ç': 'c', 'ñ': 'n',
};

/// Nome/cidade do BR Code: sem acento, maiúsculo, só ASCII imprimível, cortado no limite do campo.
String pixText(String s, int max) {
  final b = StringBuffer();
  for (final ch in s.trim().toLowerCase().split('')) {
    b.write(_accents[ch] ?? ch);
  }
  final clean = b.toString().toUpperCase().replaceAll(RegExp(r'[^A-Z0-9 .\-]'), ' ').replaceAll(RegExp(r' +'), ' ').trim();
  return clean.length <= max ? clean : clean.substring(0, max).trim();
}

/// CRC16-CCITT (poly 0x1021, início 0xFFFF), em 4 hexadecimais maiúsculos.
String crc16(String data) {
  var crc = 0xFFFF;
  for (final u in data.codeUnits) {
    crc ^= u << 8;
    for (var i = 0; i < 8; i++) {
      crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
    }
  }
  return crc.toRadixString(16).toUpperCase().padLeft(4, '0');
}

String _f(String id, String value) => '$id${value.length.toString().padLeft(2, '0')}$value';

/// O "copia e cola" do Pix estático com valor fixo: o que vai dentro do QR Code.
String buildPixPayload(PixConfig cfg, double amount) {
  if (amount <= 0 || amount > 999999999.99) throw ArgumentError('Valor inválido para o Pix: $amount');
  final key = normalizePixKey(cfg.kind, cfg.key);
  if (key == null) throw ArgumentError('Chave Pix inválida');
  final body = StringBuffer()
    ..write(_f('00', '01'))
    ..write(_f('26', _f('00', 'br.gov.bcb.pix') + _f('01', key)))
    ..write(_f('52', '0000'))
    ..write(_f('53', '986'))
    ..write(_f('54', amount.toStringAsFixed(2)))
    ..write(_f('58', 'BR'))
    ..write(_f('59', pixText(cfg.name, 25)))
    ..write(_f('60', pixText(cfg.city, 15)))
    ..write(_f('62', _f('05', '***')))
    ..write('6304');
  final s = body.toString();
  return s + crc16(s);
}
