@TestOn('vm')
library;

import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';

// Teste de integração contra um backend de verdade (não roda se RAYKA_API não estiver definido):
//   RAYKA_API=http://127.0.0.1:10014 RAYKA_USER=teste_qa RAYKA_PASS=... flutter test test/integration
// O backend precisa estar com SHOP_OWNER apontando para esse mesmo usuário (os pedidos da loja caem no painel dele).
final _base = Platform.environment['RAYKA_API'];
final _user = Platform.environment['RAYKA_USER'] ?? 'teste_qa';
final _pass = Platform.environment['RAYKA_PASS'] ?? '';

void main() {
  if (_base == null) {
    test('integração (defina RAYKA_API para rodar)', () {}, skip: 'RAYKA_API não definido');
    return;
  }

  final stamp = DateTime.now().millisecondsSinceEpoch;
  late Session owner;
  int? ingredientId, recipeId, orderId;

  setUpAll(() async {
    owner = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
  });

  group('painel da confeiteira', () {
    test('login devolve token e /api/me confirma', () async {
      await owner.loginOwner(_user, _pass);
      expect(owner.isOwner, isTrue);
      expect((await owner.ownerApi.get('/api/me'))['username'], _user);
    });

    test('login errado dá mensagem amigável', () async {
      final s = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
      await expectLater(s.loginOwner(_user, 'senha-errada-xyz'), throwsA(isA<ApiException>().having((e) => e.message, 'mensagem', 'Usuário ou senha incorretos.')));
    });

    test('resumo do mês é lido pelos modelos', () async {
      final d = Dashboard.fromJson(await owner.ownerApi.get('/api/dashboard') as Map<String, dynamic>);
      expect(d.username, _user);
      expect(d.month, matches(RegExp(r'^\d{4}-\d{2}-01$')));
    });

    test('cria ingrediente, receita com preço e lê de volta', () async {
      final api = owner.ownerApi;
      final ing = await api.post('/api/ingredients', {
        'name': 'Mobile Farinha $stamp', 'category_id': '', 'unit': 'g', 'stock_qty': '2', 'package_size': '1.000', 'package_cost': '8,50', 'min_stock': '1', 'supplier': 'QA', 'notes': '',
      }) as Map<String, dynamic>;
      ingredientId = ing['id'] as int;
      final list = [for (final e in await api.get('/api/ingredients') as List) Ingredient.fromJson(e)];
      final mine = list.firstWhere((i) => i.id == ingredientId);
      expect(mine.name, 'Mobile Farinha $stamp');
      expect(mine.stockQty, 2);

      final rec = await api.post('/api/recipes', {
        'name': 'Mobile Bolo $stamp', 'category_id': '', 'yield_units': 10, 'margin_pct': 100, 'prep_hours': 0, 'notes': '',
        'items': [{'ingredient_id': ingredientId.toString(), 'qty': 500}],
      }) as Map<String, dynamic>;
      recipeId = rec['id'] as int;
      final priced = [for (final e in await api.get('/api/recipes') as List) RecipeRow.fromJson(e)].firstWhere((r) => r.id == recipeId);
      expect(priced.pricing, isNotNull);
      expect(priced.pricing!.ingredients, closeTo(4.25, 1e-6)); // 500 g × R$ 8,50 / 1000 g
      expect(priced.pricing!.rounded, greaterThan(0));
    });

    test('foto da receita: envia, aparece na versão e remove', () async {
      final api = owner.ownerApi;
      // PNG mínimo válido (1×1)
      final png = Uint8List.fromList([
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00,
        0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xCF, 0xC0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD, 0x8D, 0xB0,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
      ]);
      await api.putBytes('/api/recipes/$recipeId/photo', png, 'image/png');
      final withPhoto = RecipeRow.fromJson(await api.get('/api/recipes/$recipeId') as Map<String, dynamic>);
      expect(withPhoto.photoV, isNotNull);
      await expectLater(api.putBytes('/api/recipes/$recipeId/photo', Uint8List.fromList(List.filled(40, 65)), 'image/png'), throwsA(isA<ApiException>().having((e) => e.status, 'status', 415)));
      await api.delete('/api/recipes/$recipeId/photo');
      expect(RecipeRow.fromJson(await api.get('/api/recipes/$recipeId') as Map<String, dynamic>).photoV, isNull);
    });

    test('pedido manual, troca de status e lista', () async {
      final api = owner.ownerApi;
      final o = await api.post('/api/orders', {
        'order_date': todayIsoForTest(), 'customer': 'Cliente QA $stamp', 'recipe_id': recipeId.toString(), 'product': 'Mobile Bolo', 'qty': '2', 'unit_price': '21,00',
        'delivery_date': '', 'status': 'novo', 'payment': 'pendente', 'notes': 'teste',
      }) as Map<String, dynamic>;
      orderId = o['id'] as int;
      await api.patch('/api/orders/$orderId', {'status': 'producao'});
      final found = [for (final e in await api.get('/api/orders') as List) OrderModel.fromJson(e)].firstWhere((x) => x.id == orderId);
      expect(found.status, 'producao');
      expect(found.total, 42);
    });

    test('metas, ajustes, custos e categorias são lidos pelos modelos', () async {
      final api = owner.ownerApi;
      final g = await api.get('/api/goals?month=2026-09-01') as Map<String, dynamic>;
      Goal.fromJson(g['goal']);
      MonthStats.fromJson(g['stats']);
      await api.put('/api/goals', {'month': '2026-09-01', 'revenue': '1.000,00', 'orders': '5', 'new_customers': '2', 'avg_ticket': '50'});
      expect(Goal.fromJson((await api.get('/api/goals?month=2026-09-01') as Map)['goal']).revenue, 1000);
      Settings.fromJson(await api.get('/api/settings') as Map<String, dynamic>);
      final costs = [for (final e in await api.get('/api/fixed-costs') as List) FixedCost.fromJson(e)];
      expect(costs, isA<List<FixedCost>>());
      final cats = [for (final e in await api.get('/api/categories') as List) Category.fromJson(e)];
      expect(cats.map((c) => c.kind).toSet(), containsAll(['ingredient', 'recipe', 'expense']));
    });

    test('sessão inválida: 401 derruba o token', () async {
      final s = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
      s.ownerApi.token = 'token-invalido';
      await expectLater(s.ownerApi.get('/api/dashboard'), throwsA(isA<ApiException>().having((e) => e.unauthorized, 'unauthorized', isTrue)));
      expect(s.isOwner, isFalse);
    });
  });

  tearDownAll(() async {
    // limpa o que o teste criou no painel
    final api = owner.ownerApi;
    try {
      for (final e in await api.get('/api/orders') as List) {
        final o = OrderModel.fromJson(e);
        if (o.customer == 'Cliente Mobile' || o.customer == 'Cliente QA $stamp') await api.delete('/api/orders/${o.id}');
      }
      if (recipeId != null) await api.delete('/api/recipes/$recipeId');
      if (ingredientId != null) await api.delete('/api/ingredients/$ingredientId');
    } catch (_) {}
  });
}

String addDays(DateTime d, int n) {
  final x = d.add(Duration(days: n));
  return '${x.year.toString().padLeft(4, '0')}-${x.month.toString().padLeft(2, '0')}-${x.day.toString().padLeft(2, '0')}';
}

String todayIsoForTest() => addDays(DateTime.now(), 0);
