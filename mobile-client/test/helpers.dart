import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/session.dart';

typedef Handler = (int, Object?) Function(http.Request req);

/// Backend de mentira: mapeia "MÉTODO /caminho" para uma resposta (status, corpo JSON).
class FakeBackend {
  FakeBackend(this.routes);
  final Map<String, Handler> routes;
  final calls = <String>[];
  final bodies = <String, Object?>{};
  final multipart = <String, String>{}; // corpo (texto) dos envios multipart, como as fotos de uma devolução

  MockClient get client => MockClient((req) async {
        final key = '${req.method} ${req.url.path}${req.url.hasQuery ? '?${req.url.query}' : ''}';
        calls.add(key);
        if (req.body.isNotEmpty && req.headers['content-type']?.contains('json') == true) bodies[key] = jsonDecode(req.body);
        final raw = latin1.decode(req.bodyBytes);
        if (raw.contains('Content-Disposition: form-data') || raw.contains('content-disposition: form-data')) multipart[key] = raw;
        final h = routes[key] ?? routes['${req.method} ${req.url.path}'];
        if (h == null) return http.Response(jsonEncode({'error': 'rota não simulada: $key'}), 404, headers: {'content-type': 'application/json'});
        final (status, body) = h(req);
        return http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});
      });

  Session session({TokenStore? store}) => Session(
        store: store ?? MemoryTokenStore(),
        owner: ApiClient(client: client, base: 'http://fake'),
        customer: ApiClient(client: client, base: 'http://fake'),
      );
}

/// Carrega as fontes da marca para os testes medirem o texto como no app de verdade.
Future<void> loadAppFonts() async {
  for (final (family, file) in [('Figtree', 'assets/fonts/Figtree.ttf'), ('Fraunces', 'assets/fonts/Fraunces.ttf')]) {
    final loader = FontLoader(family)..addFont(Future.value(ByteData.sublistView(File(file).readAsBytesSync())));
    await loader.load();
  }
}

void useBigPhone(WidgetTester t) {
  t.view.physicalSize = const Size(430, 932);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.reset);
}

// ---- dados de exemplo ------------------------------------------------------------------------------

const menuJson = {
  'items': [
    {'id': '1', 'name': 'Brigadeiro gourmet', 'price': 6.5, 'category': 'Outros'},
    {'id': '2', 'name': 'Cookie tradicional', 'price': 9.0, 'category': 'Cookies e biscoitos'},
  ],
  'groups': [
    {'id': 'cookies-e-biscoitos', 'name': 'Cookies e biscoitos', 'items': [{'id': '2', 'name': 'Cookie tradicional', 'price': 9.0, 'category': 'Cookies e biscoitos'}]},
    {'id': 'outros', 'name': 'Outros', 'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'price': 6.5, 'category': 'Outros'}]},
  ],
};

const customerJson = {
  'id': 7, 'email': 'joana@example.com', 'name': 'Joana Prado', 'phone': '', 'cep': '', 'street': '', 'number': '', 'complement': '', 'district': '', 'city': '',
};

const dashboardJson = {
  'username': 'teste_qa', 'month': '2026-09-01', 'ingredientCount': 3,
  'goal': {'revenue': 1000.0, 'orders': 0, 'new_customers': 0, 'avg_ticket': 0.0},
  'stats': {'revenue': 201.0, 'orders': 4, 'avg_ticket': 50.25, 'new_customers': 2},
  'receivable': 201.0, 'empty': false,
  'low': [
    {'id': 1, 'category_id': null, 'name': 'Farinha', 'unit': 'g', 'stock_qty': 0, 'package_size': 1000, 'package_cost': 5, 'min_stock': 1, 'supplier': '', 'notes': ''}
  ],
  'upcoming': [
    {'id': 1, 'number': 3, 'order_date': '2026-09-30', 'customer': 'Joana Prado', 'recipe_id': null, 'product': 'Bolo de chocolate', 'qty': 1, 'unit_price': 98, 'delivery_date': '2026-10-05', 'status': 'pronto', 'payment': 'pendente', 'notes': ''}
  ],
  'best': [],
};
