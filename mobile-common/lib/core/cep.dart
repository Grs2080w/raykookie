import 'dart:convert';

import 'package:http/http.dart' as http;

/// Endereço devolvido pela consulta de CEP (ViaCEP).
typedef CepResult = ({String street, String district, String city});

/// Procura o endereço de um CEP (8 números). Devolve nulo se não achar ou se estiver sem internet: a pessoa preenche na mão.
Future<CepResult?> lookupCep(String digits, {http.Client? client}) async {
  if (digits.length != 8) return null;
  try {
    final r = await (client ?? http.Client()).get(Uri.parse('https://viacep.com.br/ws/$digits/json/')).timeout(const Duration(seconds: 8));
    final j = jsonDecode(r.body) as Map<String, dynamic>;
    if (j['erro'] != null) return null;
    return (street: (j['logradouro'] ?? '') as String, district: (j['bairro'] ?? '') as String, city: (j['localidade'] ?? '') as String);
  } catch (_) {
    return null;
  }
}
