import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:raykookie_common/core/api_client.dart';

class ReceiptException implements Exception {
  ReceiptException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Link do QR code -> endereço seguro de portal do governo (*.gov.br), ou null se não for de nota fiscal.
/// QR codes antigos trazem `http://`; os portais respondem em https, que é o único que o Android deixa abrir.
Uri? nfceUri(String raw) {
  final text = raw.trim();
  if (text.isEmpty || text.length > 2000) return null;
  final u = Uri.tryParse(
    text.startsWith('http://') ? 'https://${text.substring(7)}' : text,
  );
  if (u == null || u.scheme != 'https') return null;
  final h = u.host.toLowerCase();
  return h == 'gov.br' || h.endsWith('.gov.br') ? u : null;
}

String decodePage(List<int> bytes) {
  try {
    return utf8.decode(bytes);
  } on FormatException {
    return latin1.decode(
      bytes,
    ); // portais mais antigos ainda respondem em ISO-8859-1
  }
}

/// Baixa a página da nota. É o próprio celular que acessa o portal (o servidor do raykookie não precisa).
typedef PageFetcher = Future<String> Function(Uri url);

Future<String> fetchNfcePage(Uri url) async {
  final client = http.Client();
  try {
    final res = await client
        .get(
          url,
          headers: {
            'user-agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36',
            'accept': 'text/html,application/xhtml+xml',
            'accept-language': 'pt-BR,pt;q=0.9',
          },
        )
        .timeout(const Duration(seconds: 25));
    if (res.statusCode >= 400)
      throw ReceiptException(
        'O portal da nota respondeu com erro (${res.statusCode}). Tente de novo em instantes.',
      );
    return decodePage(res.bodyBytes);
  } on TimeoutException {
    throw ReceiptException(
      'O portal da nota demorou demais para responder. Tente de novo.',
    );
  } on ReceiptException {
    rethrow;
  } catch (_) {
    throw ReceiptException(
      'Não consegui abrir a página da nota. Confira a internet e tente de novo.',
    );
  } finally {
    client.close();
  }
}

double _d(dynamic v) => v is num ? v.toDouble() : 0;

class ReceiptMatch {
  ReceiptMatch({
    required this.ingredientId,
    required this.name,
    required this.exact,
  });
  final int ingredientId;
  final String name;
  final bool
  exact; // true: soma direto; false: só uma sugestão, a confeiteira confirma
}

class AddPlan {
  AddPlan({
    required this.addQty,
    required this.packageCost,
    required this.note,
  });
  final double addQty, packageCost;
  final String note;
}

class CreatePlan {
  CreatePlan({
    required this.name,
    required this.unit,
    required this.packageSize,
    required this.packageCost,
    required this.stockQty,
  });
  final String name, unit; // unit: g | ml | un
  final double packageSize, packageCost, stockQty;
  CreatePlan copy({
    String? name,
    String? unit,
    double? packageSize,
    double? packageCost,
    double? stockQty,
  }) => CreatePlan(
    name: name ?? this.name,
    unit: unit ?? this.unit,
    packageSize: packageSize ?? this.packageSize,
    packageCost: packageCost ?? this.packageCost,
    stockQty: stockQty ?? this.stockQty,
  );
  Map<String, Object> toJson() => {
    'action': 'create',
    'name': name,
    'unit': unit,
    'package_size': packageSize,
    'package_cost': packageCost,
    'stock_qty': stockQty,
  };
}

class ReceiptItem {
  ReceiptItem({
    required this.name,
    required this.qty,
    required this.unit,
    required this.unitPrice,
    required this.total,
    this.match,
    this.add,
    required this.create,
  });
  final String name, unit;
  final double qty, unitPrice, total;
  final ReceiptMatch? match;
  final AddPlan? add;
  final CreatePlan create;

  factory ReceiptItem.fromJson(Map<String, dynamic> j) {
    final m = j['match'] as Map<String, dynamic>?;
    final a = j['add'] as Map<String, dynamic>?;
    final c = j['create'] as Map<String, dynamic>;
    return ReceiptItem(
      name: '${j['name']}',
      qty: _d(j['qty']),
      unit: '${j['unit']}',
      unitPrice: _d(j['unit_price']),
      total: _d(j['total']),
      match: m == null
          ? null
          : ReceiptMatch(
              ingredientId: (m['ingredient_id'] as num).toInt(),
              name: '${m['name']}',
              exact: m['exact'] == true,
            ),
      add: a == null
          ? null
          : AddPlan(
              addQty: _d(a['add_qty']),
              packageCost: _d(a['package_cost']),
              note: '${a['note']}',
            ),
      create: CreatePlan(
        name: '${c['name']}',
        unit: '${c['unit']}',
        packageSize: _d(c['package_size']),
        packageCost: _d(c['package_cost']),
        stockQty: _d(c['stock_qty']),
      ),
    );
  }
}

class ReceiptPreview {
  ReceiptPreview({
    required this.key,
    required this.store,
    this.date,
    this.total,
    this.alreadyImported,
    required this.items,
  });
  final String key, store;
  final String? date, alreadyImported;
  final double? total;
  final List<ReceiptItem> items;

  factory ReceiptPreview.fromJson(Map<String, dynamic> j) => ReceiptPreview(
    key: '${j['key']}',
    store: '${j['store'] ?? ''}',
    date: j['date'] as String?,
    total: j['total'] == null ? null : _d(j['total']),
    alreadyImported: j['already_imported'] as String?,
    items: [
      for (final i in (j['items'] as List))
        ReceiptItem.fromJson(i as Map<String, dynamic>),
    ],
  );
}

/// Lê a nota do QR code e lança no estoque.
class ReceiptService {
  ReceiptService(this.api, {PageFetcher? fetch})
    : _fetch = fetch ?? fetchNfcePage;
  final ApiClient api;
  final PageFetcher _fetch;

  Future<ReceiptPreview> read(String link) async {
    final uri = nfceUri(link);
    if (uri == null)
      throw ReceiptException(
        'Esse QR code não é de uma nota fiscal. Aponte para o QR code do cupom (NFC-e).',
      );
    final html = await _fetch(uri);
    final r = await api.post('/api/nota/ler', {
      'url': link.trim(),
      'html': html,
    });
    return ReceiptPreview.fromJson(r as Map<String, dynamic>);
  }

  Future<({int added, int created})> import(
    ReceiptPreview p,
    List<Map<String, Object?>> items,
  ) async {
    final r = await api.post('/api/nota/importar', {
      'key': p.key,
      'store': p.store,
      'date': p.date,
      'total': p.total,
      'items': items,
    }) as Map<String, dynamic>;
    return (
      added: (r['added'] as num?)?.toInt() ?? 0,
      created: (r['created'] as num?)?.toInt() ?? 0,
    );
  }
}
