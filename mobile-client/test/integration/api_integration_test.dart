@TestOn('vm')
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';

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
  late Session customer;
  int? ingredientId, recipeId;

  setUpAll(() async {
    owner = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
    customer = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
  });

  setUpAll(() async {
    // prepara o cardápio: uma receita com preço, criada pela conta da confeiteira
    await owner.loginOwner(_user, _pass);
    final ing = await owner.ownerApi.post('/api/ingredients', {'name': 'Mobile Farinha $stamp', 'category_id': '', 'unit': 'g', 'stock_qty': '2', 'package_size': '1.000', 'package_cost': '8,50', 'min_stock': '1', 'supplier': 'QA', 'notes': ''}) as Map<String, dynamic>;
    ingredientId = ing['id'] as int;
    final rec = await owner.ownerApi.post('/api/recipes', {'name': 'Mobile Bolo $stamp', 'category_id': '', 'yield_units': 10, 'margin_pct': 100, 'prep_hours': 0, 'notes': '', 'items': [{'ingredient_id': ingredientId.toString(), 'qty': 500}]}) as Map<String, dynamic>;
    recipeId = rec['id'] as int;
  });

  group('loja (cliente)', () {
    final email = 'mobile.$stamp@example.com';

    test('cardápio público vem das receitas do painel', () async {
      final menu = Menu.fromJson(await customer.customerApi.get('/api/menu') as Map<String, dynamic>);
      expect(menu.items.any((m) => m.name == 'Mobile Bolo $stamp'), isTrue);
      final item = menu.items.firstWhere((m) => m.name == 'Mobile Bolo $stamp');
      expect(item.price, greaterThan(0));
    });

    test('cadastro, perfil e login', () async {
      await customer.registerCustomer('Cliente Mobile', email, 'SenhaMobile2026');
      expect(customer.isCustomer, isTrue);
      expect(customer.profile?.email, email);
      final again = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
      await again.loginCustomer(email.toUpperCase(), 'SenhaMobile2026');
      expect(again.profile?.name, 'Cliente Mobile');
      await expectLater(customer.registerCustomer('Outra', email, 'SenhaMobile2026'), throwsA(isA<ApiException>().having((e) => e.status, 'status', 409)));
    });

    test('token de cliente não abre o painel', () async {
      final s = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
      s.ownerApi.token = customer.customerApi.token;
      await expectLater(s.ownerApi.get('/api/dashboard'), throwsA(isA<ApiException>().having((e) => e.status, 'status', 401)));
    });

    test('faz um pedido, o preço vem do servidor e aparece na lista e no painel', () async {
      final menu = Menu.fromJson(await customer.customerApi.get('/api/menu') as Map<String, dynamic>);
      final item = menu.items.firstWhere((m) => m.name == 'Mobile Bolo $stamp');
      final form = OrderForm(contactName: 'Cliente Mobile', phone: '(11) 91234-5678', scheduledFor: addDays(DateTime.now(), 5), timeWindow: 'tarde', paymentMethod: 'pix', notes: 'sem nozes');
      expect(validateOrder(form, {item.id: 3}, menu.items), isNull);
      final r = await customer.customerApi.post('/api/cliente/pedidos', {...form.toJson({item.id: 3}), 'items': [{'id': item.id, 'qty': 3, 'unit_price': 0.01}]}) as Map<String, dynamic>;
      final mine = [for (final e in await customer.customerApi.get('/api/cliente/pedidos') as List) ShopOrder.fromJson(e)].firstWhere((o) => o.id == r['id']);
      expect(mine.total, closeTo(item.price * 3, 1e-6)); // o preço falso enviado foi ignorado
      expect(mine.status, 'novo');
      expect(mine.items.single.qty, 3);
      // o painel recebeu, e mudar o status lá reflete para o cliente
      final panel = [for (final e in await owner.ownerApi.get('/api/orders') as List) OrderModel.fromJson(e)].where((o) => o.customer == 'Cliente Mobile');
      expect(panel, isNotEmpty);
      await owner.ownerApi.patch('/api/orders/${panel.first.id}', {'status': 'pronto'});
      final after = [for (final e in await customer.customerApi.get('/api/cliente/pedidos') as List) ShopOrder.fromJson(e)].firstWhere((o) => o.id == r['id']);
      expect(after.status, 'pronto');
    });

    test('pedido inválido volta com a mensagem do servidor', () async {
      await expectLater(
        customer.customerApi.post('/api/cliente/pedidos', {'items': []}),
        throwsA(isA<ApiException>().having((e) => e.message, 'mensagem', 'Escolha pelo menos um item do cardápio.')),
      );
    });

    test('login com Google sem credenciais responde de forma amigável', () async {
      await expectLater(customer.loginWithGoogle('x' * 30), throwsA(isA<ApiException>().having((e) => e.status, 'status', anyOf(503, 401))));
    });

    test('sair invalida o token', () async {
      final token = customer.customerApi.token;
      await customer.logoutCustomer();
      expect(customer.isCustomer, isFalse);
      final s = Session(store: MemoryTokenStore(), owner: ApiClient(base: _base), customer: ApiClient(base: _base));
      s.customerApi.token = token;
      await expectLater(s.customerApi.get('/api/cliente/me'), throwsA(isA<ApiException>()));
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
