import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/analytics.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/engagement_catalog.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late DateTime clock;
  late List<Map<String, Object?>> sent;
  Object? failWith;

  Future<Analytics> make({bool enabled = true}) async {
    SharedPreferences.setMockInitialValues({});
    clock = DateTime(2026, 10, 13, 12);
    sent = [];
    failWith = null;
    final a = Analytics(now: () => clock, send: (body) async {
      if (failWith != null) throw failWith!;
      sent.add(body);
    });
    a.configure(api: ApiClient(base: 'http://x'), prefs: await SharedPreferences.getInstance(), platform: 'android', appVersion: '1.2.3', enabled: enabled);
    return a;
  }

  test('desligada (padrão do build), não guarda nem envia nada', () async {
    final a = await make(enabled: false);
    a.screen('/');
    expect(a.pending, 0);
    await a.flush();
    expect(sent, isEmpty);
  });

  test('só entra evento do catálogo, com campos válidos (o código de cupom nunca entra)', () async {
    final a = await make();
    a.track('keylog');
    a.track('coupon_attempt', detail: 'NATAL20');
    a.track('coupon_attempt');
    a.track('cart_add', itemId: '7');
    a.track('cart_add', itemId: '7', qty: 500);
    a.track('screen_view');
    expect(a.pending, 0);
    a.track('cart_add', itemId: 'combo-3', qty: 1);
    a.track('coupon_attempt', detail: 'min_total');
    a.screen('/cliente/pedidos/123/devolucao?token=abc');
    expect(a.pending, 3);
    await a.flush();
    final body = sent.single;
    expect(body['platform'], 'android');
    expect(body['app_version'], '1.2.3');
    expect(body['session_id'], matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
    final events = body['events'] as List;
    expect(events.map((e) => (e as Map)['name']), ['cart_add', 'coupon_attempt', 'screen_view']);
    expect((events[2] as Map)['route'], '/cliente/pedidos/:id/devolucao');
    expect(jsonEncode(body), isNot(contains('NATAL20')));
    expect(a.pending, 0);
  });

  test('vista e clique de um item contam uma vez por visita; repetição em 3 s é ignorada', () async {
    final a = await make();
    a.track('menu_item_view', itemId: '7', detail: 'home_app');
    a.track('menu_item_view', itemId: '7', detail: 'home_app');
    a.track('menu_item_click', itemId: '7', detail: 'home_app');
    a.track('menu_item_click', itemId: '7', detail: 'home_app');
    a.track('menu_item_view', itemId: '8', detail: 'home_app');
    expect(a.pending, 3);
    a.track('payment_method_select', detail: 'pix');
    clock = clock.add(const Duration(seconds: 1));
    a.track('payment_method_select', detail: 'pix');
    clock = clock.add(const Duration(seconds: 5));
    a.track('payment_method_select', detail: 'pix');
    expect(a.pending, 5);
    a.track('cart_add', itemId: '7', qty: 1);
    a.track('cart_add', itemId: '7', qty: 1);
    expect(a.pending, 7);
  });

  test('telas diferentes em seguida contam as duas; a mesma tela repetida em 3 s, uma só', () async {
    final a = await make();
    a.screen('/termos');
    a.screen('/');
    a.screen('/');
    expect(a.pending, 2);
  });

  test('depois de 30 minutos parado começa outra visita (e a vista do item conta de novo)', () async {
    final a = await make();
    a.track('menu_item_view', itemId: '7', detail: 'home_app');
    await a.flush();
    final first = sent.single['session_id'];
    a.track('menu_item_view', itemId: '7', detail: 'home_app');
    expect(a.pending, 0);
    clock = clock.add(const Duration(minutes: 31));
    a.track('menu_item_view', itemId: '7', detail: 'home_app');
    await a.flush();
    expect(sent.last['session_id'], isNot(first));
    expect(sent, hasLength(2));
  });

  test('lotes de no máximo 20; sem rede os eventos voltam para a fila; lote recusado (400) é descartado', () async {
    final a = await make();
    for (var i = 0; i < 25; i++) {
      a.track('cart_add', itemId: '$i', qty: 1);
    }
    await a.flush();
    expect(sent.map((b) => (b['events'] as List).length), [20, 5]);

    failWith = ApiException(0, 'sem rede');
    a.track('cart_add', itemId: '1', qty: 1);
    await a.flush();
    expect(a.pending, 1);

    failWith = ApiException(400, 'lote inválido');
    await a.flush();
    expect(a.pending, 0);

    failWith = StateError('qualquer coisa');
    a.track('cart_add', itemId: '1', qty: 1);
    await a.flush();
    expect(a.pending, 1);

    failWith = null;
    await a.flush();
    expect(a.pending, 0);
  });

  test('"não medir" limpa a fila, bloqueia novos eventos e fica salvo', () async {
    final a = await make();
    a.track('cart_add', itemId: '1', qty: 1);
    await a.setOptOut(true);
    expect(a.optedOut, isTrue);
    expect(a.pending, 0);
    a.track('cart_add', itemId: '2', qty: 1);
    expect(a.pending, 0);
    expect((await SharedPreferences.getInstance()).getBool('rk_no_track'), isTrue);
    await a.setOptOut(false);
    a.track('cart_add', itemId: '2', qty: 1);
    expect(a.pending, 1);
  });

  test('o catálogo do app é igual ao gerado do TypeScript (rules-vectors/engagement.json)', () {
    final file = File('../backend-java/rules-vectors/engagement.json');
    final expected = jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
    final mine = {
      for (final e in engagementEvents.entries)
        e.key: {
          'route': e.value.route,
          'item': e.value.item,
          'qty': e.value.qty,
          'detail_required': e.value.detailRequired,
          'details': [...e.value.details]..sort(),
        },
    };
    expect(mine, equals(expected));
  });

  test('couponReason traduz a mensagem do servidor sem o código digitado', () {
    expect(couponReason('Cupom válido para pedidos a partir de R\$ 50,00.'), 'min_total');
    expect(couponReason('Este cupom está inativo.'), 'inactive');
    expect(couponReason('Cupom esgotado.'), 'used_up');
    expect(couponReason('Cupom não encontrado.'), 'invalid');
  });
}
