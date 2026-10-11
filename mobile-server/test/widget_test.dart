import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_gestao/app.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_gestao/core/biometric.dart';
import 'package:raykookie_gestao/core/push.dart';

import 'helpers.dart';

Future<void> pumpApp(WidgetTester t, FakeBackend be, {String at = '/painel/inicio', Session? session, PushService? push}) async {
  useBigPhone(t);
  final s = session ?? be.session();
  await t.pumpWidget(GestaoApp(session: s, theme: ThemeController(), lock: BiometricLock(session: s, auth: FakeBiometric(isAvailable: false)), initialLocation: at, push: push));
  await t.pumpAndSettle(const Duration(milliseconds: 100));
}

const legalJson = {
  'id': 'termos', 'title': 'Termos de Serviço', 'summary': 'Regras de uso.', 'updated': '2 de outubro de 2026',
  'sections': [
    {'h': '1. Quem somos', 'p': ['Texto da primeira seção.']},
    {'h': '2. O que oferecemos', 'ul': ['Item de lista um.', 'Item de lista dois.']},
  ],
};
void main() {
  setUpAll(() async {
    await loadAppFonts();
    mockInstalledVersion('1.0.0', 1);
  });

  group('gestão (confeiteira)', () {
    testWidgets('sem login, qualquer tela cai no login da confeiteira', (t) async {
      await pumpApp(t, FakeBackend({}), at: '/painel/estoque');
      expect(find.text('Bem-vinda de volta'), findsOneWidget);
    });

    testWidgets('login da confeiteira leva ao resumo do mês', (t) async {
      final be = FakeBackend({
        'POST /api/auth/login': (_) => (200, {'ok': true, 'token': 'tok-dono'}),
        'GET /api/dashboard': (_) => (200, dashboardJson),
      });
      await pumpApp(t, be, at: '/painel/entrar');
      await t.enterText(find.byType(TextField).at(0), 'teste_qa');
      await t.enterText(find.byType(TextField).at(1), 'QaSenha2026x');
      await t.tap(find.widgetWithText(FilledButton, 'Entrar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Olá, teste_qa'), findsOneWidget);
      expect(find.text('R\$ 201,00'), findsWidgets);
      expect(find.text('1× Bolo de chocolate'), findsOneWidget);
      expect(find.text('Pronto'), findsOneWidget);
    });

    testWidgets('token guardado abre o painel direto e 401 derruba a sessão', (t) async {
      var expired = false;
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/dashboard': (_) => expired ? (401, {'error': 'Sessão expirada. Entre novamente.'}) : (200, dashboardJson),
      });
      final store = MemoryTokenStore()..write('owner_token', 'tok-dono');
      final s = be.session(store: store);
      await s.load();
      expect(s.isOwner, isTrue);
      expect(s.ownerName, 'teste_qa');
      await pumpApp(t, be, at: '/painel/inicio', session: s);
      expect(find.text('Olá, teste_qa'), findsOneWidget);
      expired = true;
      await s.ownerApi.get('/api/dashboard').catchError((_) => null);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(s.isOwner, isFalse);
      expect(await store.read('owner_token'), isNull);
      expect(find.text('Bem-vinda de volta'), findsOneWidget);
    });
  });

  group('versão do app', () {
    testWidgets('a tela de login mostra a versão instalada', (t) async {
      await pumpApp(t, FakeBackend({}), at: '/painel/entrar');
      expect(find.text('Raykookie gestão · versão 1.0.0 (build 1)'), findsOneWidget);
    });

    testWidgets('"Mais" mostra a versão e avisa quando há build mais novo publicado', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/downloads': (_) => (200, {'server': {'version': '1.0.1', 'build': 2, 'size': 20971520, 'date': '2026-10-02', 'url': '/api/downloads/server'}}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/mais', session: s);
      expect(find.text('Nova versão disponível'), findsOneWidget);
      expect(find.textContaining('1.0.1 (build 2)'), findsOneWidget);
      await t.scrollUntilVisible(find.textContaining('versão 1.0.0 (build 1)'), 400, scrollable: find.byType(Scrollable).first);
      expect(find.text('Raykookie gestão · versão 1.0.0 (build 1)'), findsOneWidget);
    });
  });

  group('privacidade dos clientes (gestão)', () {
    testWidgets('o login e o "Mais" não têm termos nem política (valem só para o cliente)', (t) async {
      final be = FakeBackend({'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'})});
      await pumpApp(t, be, at: '/painel/entrar');
      expect(find.text('Termos de Serviço'), findsNothing);
      expect(find.text('Política de Privacidade'), findsNothing);
    });

    testWidgets('lista a solicitação e responde ao cliente', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/privacy-requests': (_) => (200, [
              {'id': 7, 'type': 'anonimizacao', 'details': 'Apaguem meu endereço', 'status': 'aberta', 'answer': '', 'created_at': '2026-10-01T10:00:00Z', 'due_at': '2099-10-16T10:00:00Z', 'customer': 'Maria', 'email': 'maria@x.com'}
            ]),
        'POST /api/privacy-requests/7': (_) => (200, {'ok': true}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/privacidade', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Anonimizar, bloquear ou eliminar dados'), findsOneWidget);
      expect(find.text('Apaguem meu endereço'), findsOneWidget);
      await t.tap(find.text('Responder'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.enterText(find.byType(TextField).last, 'Feito, apagamos.');
      await t.tap(find.text('Concluir'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/privacy-requests/7'));
      expect((be.bodies['POST /api/privacy-requests/7'] as Map)['answer'], 'Feito, apagamos.');
    });
  });

  group('link de pagamento (gestão)', () {
    Map<String, dynamic> order(int id, {String payment = 'pendente', String status = 'novo', int? shop, num qty = 2, num price = 40.0}) => {
          'id': id, 'number': 12, 'order_date': '2026-10-03', 'customer': 'Ana', 'recipe_id': null, 'product': 'Bolo de cenoura', 'qty': qty, 'unit_price': price,
          'delivery_date': '2026-10-10', 'status': status, 'payment': payment, 'notes': '', 'address': '', 'shop_order_id': shop,
        };
    final share = 'Compartilhar link de pagamento';

    Future<FakeBackend> open(WidgetTester t, List<Map<String, dynamic>> orders, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, orders),
        'GET /api/recipes': (_) => (200, []),
        ...extra,
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    testWidgets('o botão só aparece em pedido registrado à mão, não pago e não cancelado (pendente ou sinal)', (t) async {
      await open(t, [order(5), order(6, payment: 'sinal'), order(7, payment: 'pago'), order(8, shop: 3), order(9, status: 'cancelado')]);
      expect(find.text(share, skipOffstage: false), findsNWidgets(2));
    });

    testWidgets('compartilhar: o valor editado vai para a API e a mensagem leva o link', (t) async {
      String? shared;
      final orig = sharePlatform;
      sharePlatform = (p) async => shared = p.text;
      addTearDown(() => sharePlatform = orig);
      final be = await open(t, [order(5)], extra: {
        'POST /api/orders/5/pagamento': (_) => (200, {'url': 'https://raykookie.kntap.com.br/pagar/abc', 'amount': 30.0}),
      });
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(TextField, '80,00'), findsOneWidget); // vem com o total
      await t.enterText(find.byType(TextField).last, '30,00');
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/5/pagamento'], {'amount': '30,00'});
      expect(shared, allOf(contains('pedido #12'), contains('30,00'), contains('https://raykookie.kntap.com.br/pagar/abc')));
    });

    testWidgets('total com meio centavo (0,5 x 45,99 = 22,995): o valor pré-preenchido é aceito', (t) async {
      final orig = sharePlatform;
      sharePlatform = (p) async {};
      addTearDown(() => sharePlatform = orig);
      final be = await open(t, [order(5, qty: 0.5, price: 45.99)], extra: {
        'POST /api/orders/5/pagamento': (_) => (200, {'url': 'https://raykookie.kntap.com.br/pagar/abc', 'amount': 23.0}),
      });
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(TextField, '23,00'), findsOneWidget);
      await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/5/pagamento'], {'amount': '23,00'});
    });

    testWidgets('valor acima do total ou vazio não chama a API', (t) async {
      final be = await open(t, [order(5)], extra: {'POST /api/orders/5/pagamento': (_) => (200, {'url': 'x', 'amount': 1.0})});
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      for (final v in ['100,00', '0', '']) {
        await t.enterText(find.byType(TextField).last, v);
        await t.pump();
        await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
        await t.pump(const Duration(milliseconds: 100));
      }
      expect(be.calls.where((c) => c.startsWith('POST /api/orders/5/pagamento')), isEmpty);
    });

    testWidgets('erro da API aparece e a folha continua aberta', (t) async {
      final be = await open(t, [order(5)], extra: {'POST /api/orders/5/pagamento': (_) => (409, {'error': 'Este pedido já está pago.'})});
      await t.tap(find.text(share));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Compartilhar link'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/orders/5/pagamento'));
      expect(find.widgetWithText(FilledButton, 'Compartilhar link'), findsOneWidget);
    });
  });

  group('devoluções (gestão)', () {
    Map<String, dynamic> row({String status = 'solicitado', bool priority = false}) => {
          'id': 7, 'order_id': 4, 'code': 4821, 'customer': 'Maria', 'kind': 'solicitado', 'status': status, 'reason_code': 'avariado', 'priority': priority,
          'requested_amount': 30.0, 'approved_amount': null, 'fail_reason': status == 'falhou' ? 'saldo' : null, 'created_at': '2026-10-03T12:00:00.000Z', 'photos': 2,
        };
    Map<String, dynamic> detail({String status = 'solicitado'}) => {
          ...row(status: status), 'phone': '11999999999', 'order_total': 100.0, 'refunded_total': 0.0, 'description': 'Chegou todo amassado e derretido.', 'reason': 'Chegou amassado, quebrado ou derretido',
          'items': [{'name': 'Brownie', 'qty': 1}], 'photos': [], 'events': [{'at': '2026-10-03T12:00:00.000Z', 'actor': 'cliente', 'type': 'devolucao_solicitada', 'data': {}}],
          'decision_reason': '', 'decision_note': '', 'attempts': 1, 'next_try_at': null,
        };

    Future<FakeBackend> open(WidgetTester t, {String at = '/painel/devolucoes', Map<String, Handler> extra = const {}, String status = 'solicitado'}) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/devolucoes?status=aguardando': (_) => (200, {'list': [row(priority: true)], 'counts': {'aguardando': 1, 'andamento': 0, 'concluidas': 0, 'negadas': 0}, 'pending_amount': 12.5}),
        'GET /api/devolucoes/7': (_) => (200, detail(status: status)),
        ...extra,
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: at, session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    testWidgets('lista a solicitação com prioridade, motivo, fotos e o aviso de saldo', (t) async {
      await open(t);
      expect(find.text('Pedido #4821 · Maria'), findsOneWidget);
      expect(find.text('PRIORIDADE'), findsOneWidget);
      expect(find.textContaining('Chegou amassado, quebrado ou derretido'), findsOneWidget);
      expect(find.textContaining('2 foto(s)'), findsOneWidget);
      expect(find.textContaining('Mantenha R\$ 12,50 disponível'), findsNothing); // o aviso diz "esse valor"
      expect(find.textContaining('R\$ 12,50 em devoluções em processamento'), findsOneWidget);
    });

    testWidgets('aprova parcial: o valor editado e a nota vão para a API', (t) async {
      final be = await open(t, extra: {'POST /api/devolucoes/7/decisao': (_) => (200, {'ok': true, 'status': 'aprovado'})});
      await t.tap(find.text('Pedido #4821 · Maria'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Chegou todo amassado e derretido.'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Aprovar total'), findsOneWidget);
      await t.enterText(find.byType(TextField).first, '10,00');
      await t.enterText(find.byType(TextField).last, 'Devolvemos parte');
      await t.pump();
      expect(find.widgetWithText(FilledButton, 'Aprovar parcial'), findsOneWidget);
      await t.tap(find.widgetWithText(FilledButton, 'Aprovar parcial'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/devolucoes/7/decisao'], {'approve': true, 'amount': '10,00', 'note': 'Devolvemos parte'});
    });

    testWidgets('negar exige escolher o motivo da lista', (t) async {
      final be = await open(t, extra: {'POST /api/devolucoes/7/decisao': (_) => (200, {'ok': true, 'status': 'negado'})});
      await t.tap(find.text('Pedido #4821 · Maria'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(OutlinedButton, 'Negar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(FilledButton, 'Negar devolução'), findsOneWidget);
      await t.tap(find.text('As fotos não mostram o problema'));
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Negar devolução'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/devolucoes/7/decisao'], {'approve': false, 'code': 'sem_comprovacao', 'note': ''});
    });

    testWidgets('devolução que falhou por falta de saldo mostra o motivo real e "Tentar de novo"', (t) async {
      final be = await open(t, status: 'falhou', extra: {'POST /api/devolucoes/7/tentar': (_) => (200, {'ok': true, 'status': 'concluido'})});
      await t.tap(find.text('Pedido #4821 · Maria'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.textContaining('Falta saldo no Mercado Pago'), findsWidgets);
      await t.tap(find.widgetWithText(FilledButton, 'Tentar de novo'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/devolucoes/7/tentar'));
    });

    testWidgets('pedido da loja: "Cancelar e devolver o valor pago" pede o motivo e chama a API', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [
              {'id': 1, 'number': 5, 'order_date': '2026-10-03', 'customer': 'Maria', 'recipe_id': null, 'product': 'Brownie', 'qty': 2, 'unit_price': 50, 'delivery_date': '2026-10-10', 'status': 'novo', 'payment': 'pago', 'notes': '', 'address': 'Retirada no local',
               'shop_order_id': 4, 'shop_code': 4821, 'shop_paid': true, 'shop_total': 100, 'shop_refunded': 0},
            ]),
        'GET /api/recipes': (_) => (200, []),
        'POST /api/orders/shop/4/cancelar': (_) => (200, {'ok': true, 'refunded': 100.0}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar e devolver o valor pago'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.textContaining('o valor de R\$ 100,00 volta para a cliente automaticamente'), findsOneWidget);
      await t.tap(find.text('Faltou ingrediente'));
      await t.pump();
      expect(find.text('Mensagem para a cliente (opcional)'), findsOneWidget);
      await t.enterText(find.byType(TextField).last, 'O chocolate acabou.');
      await t.tap(find.widgetWithText(FilledButton, 'Cancelar e devolver R\$ 100,00'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/shop/4/cancelar'], {'code': 'falta_ingrediente', 'note': 'O chocolate acabou.'});
    });

    Map<String, dynamic> shopOrder({String status = 'entregue', Map<String, dynamic>? refund, Map<String, dynamic>? cancel, double? refunded, int id = 1, int shopId = 4}) => {
          'id': id, 'number': 5, 'order_date': '2026-10-03', 'customer': 'Maria', 'recipe_id': null, 'product': 'Brownie', 'qty': 2, 'unit_price': 50, 'delivery_date': '2026-10-10',
          'status': status, 'payment': 'pago', 'notes': '', 'address': 'Retirada no local', 'shop_order_id': shopId, 'shop_code': 4821, 'shop_paid': true, 'shop_total': 100,
          'shop_refunded': refunded ?? (cancel != null ? 100 : 0), 'shop_cancel': cancel, 'shop_refund': refund,
        };

    testWidgets('card do pedido: devolução aguardando aparece com motivo e fotos, e "Ver e decidir" abre o detalhe', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [shopOrder(refund: {'id': 7, 'kind': 'solicitado', 'status': 'solicitado', 'reason_code': 'avariado', 'requested_amount': 49.0, 'approved_amount': null, 'photos': 2})]),
        'GET /api/recipes': (_) => (200, []),
        'GET /api/devolucoes/7': (_) => (200, detail()),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('1 devolução aguardando sua decisão'), findsOneWidget);
      expect(find.text('DEVOLUÇÃO AGUARDANDO'), findsOneWidget);
      expect(find.text('Pede R\$ 49,00 · Chegou amassado, quebrado ou derretido · 2 foto(s)'), findsOneWidget);
      await t.tap(find.text('Ver e decidir'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.text('Chegou todo amassado e derretido.'), findsOneWidget); // o detalhe
      expect(be.calls, contains('GET /api/devolucoes/7'));
    });

    testWidgets('card do pedido cancelado: quem cancelou, o motivo, a mensagem e o valor devolvido', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [shopOrder(status: 'cancelado', cancel: {'by': 'loja', 'reason': 'Faltou ingrediente', 'note': 'acabou o chocolate'})]),
        'GET /api/recipes': (_) => (200, []),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.textContaining('Cancelado por você: Faltou ingrediente (acabou o chocolate)'), findsOneWidget);
      expect(find.textContaining('R\$ 100,00 devolvidos.'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Cancelar e devolver o valor pago'), findsNothing);
    });

    Future<void> openOrders(WidgetTester t, List<Map<String, dynamic>> orders) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, orders),
        'GET /api/recipes': (_) => (200, []),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
    }

    testWidgets('caso #38118: devolução total concluída encerra o pedido; sem cancelar, devolver, excluir nem mudar status', (t) async {
      await openOrders(t, [
        shopOrder(
          status: 'cancelado',
          refunded: 100,
          refund: {'id': 4, 'kind': 'solicitado', 'status': 'concluido', 'reason_code': 'desisti', 'requested_amount': 100.0, 'approved_amount': 100.0, 'photos': 0},
          cancel: {'by': 'sistema', 'code': 'devolucao_total', 'reason': 'Todo o valor foi devolvido', 'note': ''},
        ),
      ]);
      expect(find.text('DEVOLUÇÃO CONCLUÍDA'), findsOneWidget);
      expect(find.text('R\$ 100,00 devolvidos · Desisti do pedido (já está em produção)'), findsOneWidget);
      expect(find.textContaining('Pedido encerrado: todo o valor foi devolvido'), findsOneWidget);
      expect(find.textContaining('Cancelar'), findsNothing);
      expect(find.text('Devolver valor'), findsNothing);
      expect(find.byTooltip('Excluir pedido 5'), findsNothing);
      expect(find.byTooltip('Editar pedido 5'), findsNothing);
      await t.tap(find.byTooltip('Mudar status'));
      await t.pumpAndSettle();
      expect(find.text('Em produção'), findsNothing); // menu travado: não abre
    });

    testWidgets('com devolução aguardando, o card só oferece "Ver e decidir" (nada de cancelar ou devolver por fora)', (t) async {
      await openOrders(t, [shopOrder(status: 'producao', refund: {'id': 7, 'kind': 'solicitado', 'status': 'solicitado', 'reason_code': 'desisti', 'requested_amount': 100.0, 'approved_amount': null, 'photos': 0})]);
      expect(find.text('Ver e decidir'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Cancelar e devolver o valor pago'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, 'Devolver valor'), findsNothing);
    });

    testWidgets('devolução parcial concluída: faixa com o quanto voltou e o cancelar mostra só o que resta', (t) async {
      await openOrders(t, [shopOrder(status: 'producao', refunded: 20, refund: {'id': 8, 'kind': 'loja', 'status': 'concluido', 'reason_code': 'outro', 'requested_amount': 20.0, 'approved_amount': 20.0, 'photos': 0})]);
      expect(find.text('DEVOLUÇÃO CONCLUÍDA (PARCIAL)'), findsOneWidget);
      expect(find.text('R\$ 20,00 de R\$ 100,00 devolvidos'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Cancelar e devolver R\$ 80,00'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Devolver valor'), findsOneWidget);
      expect(find.byTooltip('Excluir pedido 5'), findsNothing); // pedido do app nunca é excluído
    });

    testWidgets('cancelar com "Outro motivo" só libera o botão depois de explicar o motivo', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [shopOrder(status: 'novo')]),
        'GET /api/recipes': (_) => (200, []),
        'POST /api/orders/shop/4/cancelar': (_) => (200, {'ok': true, 'refunded': 100.0}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar e devolver o valor pago'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.text('Outro motivo'));
      await t.pump();
      final btn = find.widgetWithText(FilledButton, 'Cancelar e devolver R\$ 100,00');
      expect(t.widget<FilledButton>(btn).onPressed, isNull); // sem explicação, nada a enviar
      await t.enterText(find.byType(TextField).last, 'A cliente pediu para remarcar');
      await t.pump();
      expect(t.widget<FilledButton>(btn).onPressed, isNotNull);
      await t.tap(btn);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/shop/4/cancelar'], {'code': 'outro', 'note': 'A cliente pediu para remarcar'});
    });

    testWidgets('depois de negar a devolução, "Devolver valor" envia o valor digitado (não acusa "Informe o valor")', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [shopOrder(status: 'producao', refund: {'id': 7, 'kind': 'solicitado', 'status': 'negado', 'reason_code': 'avariado', 'requested_amount': 49.0, 'approved_amount': null, 'photos': 0})]),
        'GET /api/recipes': (_) => (200, []),
        'POST /api/orders/shop/4/devolver': (_) => (200, {'ok': true, 'refunded': 30.0}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(OutlinedButton, 'Devolver valor'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.enterText(find.byType(TextField).at(find.byType(TextField).evaluate().length - 2), '30,00');
      await t.enterText(find.byType(TextField).last, 'Faltou um brownie');
      await t.tap(find.widgetWithText(FilledButton, 'Devolver'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/orders/shop/4/devolver'], {'amount': '30,00', 'note': 'Faltou um brownie'});
    });

    testWidgets('devolução parada por falta de saldo aparece no card e abre o detalhe', (t) async {
      await openOrders(t, [shopOrder(status: 'producao', refund: {'id': 9, 'kind': 'solicitado', 'status': 'falhou', 'fail_reason': 'saldo', 'reason_code': 'avariado', 'requested_amount': 30.0, 'approved_amount': 30.0, 'photos': 1})]);
      expect(find.text('DEVOLUÇÃO PARADA'), findsOneWidget);
      expect(find.text('R\$ 30,00 · falta saldo no Mercado Pago'), findsOneWidget);
      expect(find.text('Ver e tentar de novo'), findsOneWidget);
    });

    testWidgets('escolher "Cancelado" no status de um pedido do app abre a folha do motivo (não muda direto)', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, [shopOrder(status: 'producao')]),
        'GET /api/recipes': (_) => (200, []),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.byTooltip('Mudar status'));
      await t.pumpAndSettle();
      await t.tap(find.text('Cancelado').last);
      await t.pumpAndSettle();
      expect(find.text('Cancelar pedido #4821'), findsOneWidget);
      expect(be.calls.where((c) => c.startsWith('PATCH')), isEmpty);
    });

    testWidgets('notificação de devolução abre o detalhe por cima de Pedidos (com voltar)', (t) async {
      expect(PushService.routeFor({'type': 'devolucao', 'refundId': '7'}), '/painel/devolucoes/7');
      expect(PushService.routeFor({'type': 'privacidade'}), '/painel/privacidade');
      expect(PushService.routeFor({'type': 'preco'}), '/painel/inicio');
      expect(PushService.routeFor({'type': 'pedido_cancelado', 'orderId': '3'}), '/painel/pedidos');
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/dashboard': (_) => (500, {'error': 'x'}),
        'GET /api/orders': (_) => (200, [shopOrder()]),
        'GET /api/recipes': (_) => (200, []),
        'GET /api/devolucoes/7': (_) => (200, detail()),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      final push = PushService(session: s);
      await pumpApp(t, be, at: '/painel/inicio', session: s, push: push);
      push.onOpen!(PushService.routeFor({'type': 'devolucao', 'refundId': '7'}));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.text('Chegou todo amassado e derretido.'), findsOneWidget);
      await t.tap(find.byTooltip('Voltar'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.text('Pedidos'), findsWidgets); // voltou para Pedidos
    });

    testWidgets('selo no "Mais" com o número de devoluções aguardando', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/orders': (_) => (200, []),
        'GET /api/recipes': (_) => (200, []),
        'GET /api/devolucoes?status=aguardando': (_) => (200, {'list': [], 'counts': {'aguardando': 3}, 'pending_amount': 0}),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/pedidos', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.descendant(of: find.byType(Badge), matching: find.text('3')), findsWidgets);
    });
  });

  group('preços e fornecedores (gestão)', () {
    Future<FakeBackend> open(WidgetTester t, String at, Map<String, Handler> routes) async {
      final be = FakeBackend({'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}), ...routes});
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: at, session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    testWidgets('fornecedores: lista, mostra "busca ligada" e o aviso de revisão', (t) async {
      await open(t, '/painel/fornecedores', {
        'GET /api/suppliers': (_) => (200, [
              {'id': 1, 'name': 'Romafele', 'kind': 'site', 'url': 'https://www.romafele.com.br', 'address': '', 'notes': '', 'scraper': 'tray', 'last_ok_at': null, 'last_error': ''},
              {'id': 2, 'name': 'Mercado do Zé', 'kind': 'loja', 'url': '', 'address': 'Rua A, 10', 'notes': '', 'scraper': null, 'last_ok_at': null, 'last_error': ''},
              {'id': 3, 'name': 'Outro site', 'kind': 'site', 'url': 'https://x.com', 'address': '', 'notes': '', 'scraper': 'tray', 'last_ok_at': null, 'last_error': 'O site respondeu 503'},
            ]),
      });
      expect(find.text('Romafele'), findsOneWidget);
      expect(find.text('Busca de preços ligada'), findsNWidgets(2));
      expect(find.text('Rua A, 10'), findsOneWidget);
      expect(find.textContaining('Precisa de revisão: O site respondeu 503'), findsOneWidget);
    });

    testWidgets('alerta no início: "Aplicar" fixa o preço sugerido', (t) async {
      var applied = false;
      final be = await open(t, '/painel/inicio', {
        'GET /api/dashboard': (_) => (200, {
              'username': 'Rayka', 'month': '2026-10', 'ingredientCount': 1, 'goal': {'revenue': 0, 'orders': 0, 'new_customers': 0, 'avg_ticket': 0},
              'stats': {'revenue': 0, 'profit': 0, 'orders': 0, 'avg_ticket': 0, 'new_customers': 0}, 'receivable': 0, 'empty': false, 'low': [], 'upcoming': [], 'best': [],
              'refundsWaiting': 0, 'refundsPending': 0, 'refundsFailed': 0,
            }),
        'GET /api/price-alerts': (_) => (200, applied ? [] : [
              {'id': 5, 'recipe_id': 3, 'recipe': 'Brownie', 'kind': 'margem', 'before_value': 52.4, 'after_value': 38.1, 'suggested': 9.5, 'cause': 'Chocolate: R\$ 32,00 → R\$ 40,00/kg'}
            ]),
        'POST /api/price-alerts/5/apply': (_) { applied = true; return (200, {'ok': true}); },
      });
      expect(find.text('Brownie: margem caiu de 52,4% para 38,1%'), findsOneWidget);
      expect(find.textContaining('Chocolate: R\$ 32,00'), findsOneWidget);
      await t.tap(find.text('Aplicar R\$ 9,50'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/price-alerts/5/apply'));
      expect(find.text('Ingredientes mudaram de preço'), findsNothing);
    });

    testWidgets('preços do ingrediente: mais barato primeiro, "usar este preço" e pesquisa nos sites', (t) async {
      final be = await open(t, '/painel/estoque', {
        'GET /api/ingredients': (_) => (200, [
              {'id': 9, 'category_id': null, 'name': 'Chocolate', 'unit': 'g', 'stock_qty': 2, 'package_size': 1000, 'package_cost': 32, 'min_stock': 1, 'supplier': '', 'notes': ''}
            ]),
        'GET /api/categories?type=ingredient': (_) => (200, []),
        'GET /api/categories': (_) => (200, []),
        'GET /api/ingredients/9/prices': (_) => (200, {
              'ingredient': {'id': 9, 'name': 'Chocolate', 'unit': 'g', 'package_cost': 32, 'package_size': 1000, 'price_updated_at': null}, 'current_per_base': 0.032,
              'quotes': [
                {'id': 1, 'supplier_id': 2, 'supplier': 'Mercado do Zé', 'kind': 'loja', 'price': 29.9, 'pack_size': 1000, 'pack_unit': 'g', 'source': 'manual', 'at': '2026-10-02T12:00:00Z', 'per_base': 0.0299, 'product': null, 'url': null},
                {'id': 2, 'supplier_id': 1, 'supplier': 'Romafele', 'kind': 'site', 'price': 38, 'pack_size': 1000, 'pack_unit': 'g', 'source': 'scraper', 'at': '2026-10-02T12:00:00Z', 'per_base': 0.038, 'product': 'Chocolate em pó 1kg', 'url': 'https://r/x'},
              ],
              'history': [],
            }),
        'GET /api/suppliers': (_) => (200, []),
        'POST /api/ingredients/9/use-price': (_) => (200, {'ok': true, 'alerts': 0}),
        'POST /api/prices/search': (_) => (200, {
              'term': 'Chocolate', 'unit': 'g', 'errors': [],
              'results': [
                {'supplier_id': 1, 'supplier': 'Romafele', 'products': [
                  {'name': 'Chocolate em pó 1kg', 'url': 'https://r/choc', 'price': 38.0, 'size': 1000.0, 'unit': 'g', 'available': true, 'per_base': 0.038, 'match_unit': true},
                  {'name': 'Chocolate em barra', 'url': 'https://r/barra', 'price': 9.0, 'size': null, 'unit': null, 'available': true, 'per_base': null, 'match_unit': false},
                ]},
              ],
            }),
        'POST /api/prices/link': (_) => (201, {'ok': true, 'quote_id': 7}),
      });
      await t.tap(find.byTooltip('Preços de Chocolate'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.text('Mais barato'), findsOneWidget);
      expect(find.textContaining('R\$ 29,90 por 1.000 g · R\$ 29,90/kg'), findsOneWidget);
      await t.tap(find.widgetWithText(OutlinedButton, 'Usar este preço').first);
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(be.bodies['POST /api/ingredients/9/use-price'], {'quote_id': 1});
      await t.tap(find.text('Buscar'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(find.text('Chocolate em barra'), findsOneWidget);
      expect(find.text('Tamanho da embalagem em g'), findsOneWidget); // sem tamanho no nome: a confeiteira informa
      await t.tap(find.widgetWithText(OutlinedButton, 'Ligar ao ingrediente').first);
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect((be.bodies['POST /api/prices/link'] as Map)['size'], 1000);
    });
  });

  group('endereço de retirada (gestão)', () {
    Map<String, dynamic> addr(int id, String label, {bool active = false, String instructions = ''}) => {
          'id': id, 'label': label, 'cep': '01310100', 'street': 'Av. Paulista', 'number': '$id', 'complement': '', 'district': 'Bela Vista', 'city': 'São Paulo',
          'instructions': instructions, 'is_active': active, 'address': 'Av. Paulista, $id - Bela Vista, São Paulo',
        };

    Future<FakeBackend> open(WidgetTester t, List<Map<String, dynamic>> Function() list, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}), 'GET /api/pickup-addresses': (_) => (200, list()), ...extra});
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/enderecos', session: s);
      return be;
    }

    testWidgets('lista os endereços guardados e marca o de retirada atual', (t) async {
      await open(t, () => [addr(1, 'Ateliê', active: true, instructions: 'Portão azul'), addr(2, 'Casa')]);
      expect(find.text('Endereço de retirada'), findsOneWidget); // título da tela
      expect(find.text('Ateliê'), findsOneWidget);
      expect(find.text('Casa'), findsOneWidget);
      expect(find.text('Retirada atual'), findsOneWidget);
      expect(find.text('Portão azul'), findsOneWidget);
      expect(find.text('Adicionar endereço'), findsOneWidget);
    });

    testWidgets('sem endereços, explica e oferece adicionar', (t) async {
      await open(t, () => []);
      expect(find.text('Nenhum endereço de retirada'), findsOneWidget);
      expect(find.text('Adicionar endereço'), findsOneWidget);
    });

    testWidgets('tocar em outro endereço o escolhe como o de retirada (PATCH active) e recarrega', (t) async {
      var active = 1;
      final be = await open(
        t,
        () => [addr(1, 'Ateliê', active: active == 1), addr(2, 'Casa', active: active == 2)],
        extra: {'PATCH /api/pickup-addresses/2': (_) { active = 2; return (200, addr(2, 'Casa', active: true)); }},
      );
      await t.tap(find.text('Casa'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['PATCH /api/pickup-addresses/2'], {'active': true});
      expect(find.text('Retirada atual'), findsOneWidget);
    });

    testWidgets('"Adicionar endereço" abre o formulário e salva com todos os campos', (t) async {
      final be = await open(t, () => [addr(1, 'Ateliê', active: true)], extra: {'POST /api/pickup-addresses': (_) => (201, addr(3, 'Loja'))});
      await t.tap(find.widgetWithText(FilledButton, 'Adicionar endereço'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Apelido do endereço'), findsOneWidget);
      expect(find.text('Usar como endereço de retirada'), findsOneWidget);
      final fields = find.byType(TextField);
      await t.enterText(fields.at(0), 'Loja');
      await t.enterText(fields.at(2), 'Rua das Flores');
      await t.enterText(fields.at(3), '120');
      await t.enterText(fields.at(5), 'Jardim Paulista');
      await t.enterText(fields.at(6), 'São Paulo');
      await t.tap(find.widgetWithText(FilledButton, 'Salvar endereço'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/pickup-addresses'], containsPair('street', 'Rua das Flores'));
      expect(be.bodies['POST /api/pickup-addresses'], containsPair('label', 'Loja'));
      expect(be.bodies['POST /api/pickup-addresses'], containsPair('active', false)); // o atual continua sendo o Ateliê
    });

    testWidgets('endereço sem rua não é enviado', (t) async {
      final be = await open(t, () => [addr(1, 'Ateliê', active: true)]);
      await t.tap(find.widgetWithText(FilledButton, 'Adicionar endereço'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Salvar endereço'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.text('Informe a rua.'), findsOneWidget);
      expect(be.calls.where((c) => c.startsWith('POST')), isEmpty);
    });

    testWidgets('o primeiro endereço já nasce como o atual', (t) async {
      await open(t, () => []);
      await t.tap(find.widgetWithText(FilledButton, 'Adicionar endereço'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('É o primeiro endereço, então já fica como o atual.'), findsOneWidget);
    });

    testWidgets('excluir o endereço atual com outros guardados mostra o aviso do servidor', (t) async {
      await open(
        t,
        () => [addr(1, 'Ateliê', active: true), addr(2, 'Casa')],
        extra: {'DELETE /api/pickup-addresses/1': (_) => (409, {'error': 'Este é o endereço de retirada atual. Escolha outro como atual antes de excluir este.'})},
      );
      await t.tap(find.byTooltip('Excluir Ateliê'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Excluir'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('Escolha outro como atual'), findsOneWidget);
    });

    testWidgets('"Mais" tem o atalho para o endereço de retirada', (t) async {
      final be = FakeBackend({'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}), 'GET /api/pickup-addresses': (_) => (200, [])});
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/mais', session: s);
      await t.scrollUntilVisible(find.text('Onde o cliente retira os pedidos'), 300, scrollable: find.byType(Scrollable).first);
      await t.tap(find.text('Onde o cliente retira os pedidos'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Nenhum endereço de retirada'), findsOneWidget);
    });
  });

  group('compartilhar cardápio (gestão)', () {
    Future<void> openRecipes(WidgetTester t, List<Map<String, dynamic>> recipes) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/recipes': (_) => (200, recipes),
        'GET /api/categories': (_) => (200, []),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/receitas', session: s);
    }

    final recipe = {'id': 1, 'name': 'Cookie tradicional', 'category_id': null, 'yield_units': 20, 'margin_pct': 120, 'prep_hours': 1.5, 'notes': '', 'unit_weight_g': 60, 'photo_v': null, 'items': [], 'pricing': null};

    testWidgets('com receitas, a tela mostra o botão e ele manda o link do cardápio', (t) async {
      String? sent;
      sharePlatform = (params) async => sent = params.text;
      await openRecipes(t, [recipe]);
      expect(find.text('Compartilhar cardápio'), findsOneWidget);
      await t.tap(find.text('Compartilhar cardápio'));
      await t.pump(const Duration(milliseconds: 300));
      expect(sent, contains('/cardapio'));
    });

    testWidgets('sem receitas não há cardápio para compartilhar: o botão não aparece', (t) async {
      await openRecipes(t, []);
      expect(find.text('Compartilhar cardápio'), findsNothing);
      expect(find.text('Nenhuma receita ainda'), findsOneWidget);
    });
  });

  group('várias receitas por produto (gestão)', () {
    testWidgets('abas Receita 1 ★ e Chocolate; trocar a padrão e salvar grava produto, receitas e a padrão nova', (t) async {
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/ingredients': (_) => (200, [
              {'id': 1, 'name': 'Farinha', 'unit': 'g', 'package_size': 1000, 'package_cost': 10, 'stock_qty': 0, 'min_stock': 0},
              {'id': 2, 'name': 'Cacau', 'unit': 'g', 'package_size': 1000, 'package_cost': 50, 'stock_qty': 0, 'min_stock': 0},
            ]),
        'GET /api/categories': (_) => (200, []),
        'GET /api/settings': (_) => (200, {'hourly_rate': 0, 'monthly_units': 100}),
        'GET /api/fixed-costs': (_) => (200, []),
        'GET /api/recipes/5': (_) => (200, {
              'id': 5, 'name': 'Cookie', 'category_id': null, 'yield_units': 10, 'margin_pct': 100, 'prep_hours': 0, 'notes': '',
              'unit_weight_g': 0, 'sale_price': null, 'photo_v': null, 'items': [{'ingredient_id': 1, 'qty': 1000}],
              'variants': [
                {'id': 11, 'name': 'Receita 1', 'yield_units': 10, 'prep_hours': 0, 'items': [{'ingredient_id': 1, 'qty': 1000}], 'is_default': true},
                {'id': 12, 'name': 'Chocolate', 'yield_units': 20, 'prep_hours': 1, 'items': [{'ingredient_id': 2, 'qty': 300}], 'is_default': false},
              ],
            }),
        'PUT /api/recipes/5': (_) => (200, {'id': 5}),
        'PUT /api/recipes/5/variants/11': (_) => (200, []),
        'PUT /api/recipes/5/variants/12': (_) => (200, []),
        'POST /api/recipes/5/variants/12/default': (_) => (200, []),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/receitas/5', session: s);

      // ── 1. As duas abas, com a padrão marcada.
      await t.pumpAndSettle();
      await t.scrollUntilVisible(find.text('Receita 1 ★'), 300, scrollable: find.byType(Scrollable).first);
      expect(find.text('Receita 1 ★'), findsOneWidget);
      expect(find.text('Chocolate'), findsOneWidget);

      // ── 2. Abre Chocolate e marca como padrão.
      await t.ensureVisible(find.text('Chocolate'));
      await t.tap(find.text('Chocolate'));
      await t.pumpAndSettle();
      await t.ensureVisible(find.byKey(const Key('variant_make_default')));
      await t.tap(find.byKey(const Key('variant_make_default')));
      await t.pumpAndSettle();
      expect(find.textContaining('★ Receita padrão'), findsOneWidget);

      // ── 3. Salvar: produto com a padrão antiga, cada receita, e a troca de padrão.
      await t.ensureVisible(find.text('Salvar receita'));
      await t.tap(find.text('Salvar receita'));
      await t.pumpAndSettle();
      expect(be.calls, containsAllInOrder([
        'PUT /api/recipes/5', 'PUT /api/recipes/5/variants/11', 'PUT /api/recipes/5/variants/12',
        'POST /api/recipes/5/variants/12/default',
      ]));
      expect((be.bodies['PUT /api/recipes/5'] as Map)['items'], [{'ingredient_id': '1', 'qty': 1000.0}]);
      expect((be.bodies['PUT /api/recipes/5/variants/12'] as Map)['name'], 'Chocolate');
    });
  });

  group('combos na página do Cardápio (gestão)', () {
    testWidgets('+ Novo combo: produtos com quantidade, sugerido ao vivo (soma - 10%) e salvar', (t) async {
      final page = {
        'discount_pct': 10, 'min_margin_pct': 30,
        'products': [
          {'id': 1, 'name': 'Cookie', 'price': 9, 'cost': 3, 'photo_v': null, 'on_menu': true},
          {'id': 2, 'name': 'Brownie', 'price': 12, 'cost': 4, 'photo_v': null, 'on_menu': true},
        ],
        'combos': [],
      };
      final be = FakeBackend({
        'GET /api/me': (_) => (200, {'id': 2, 'username': 'teste_qa'}),
        'GET /api/recipes/cardapio': (_) => (200, [
              {'id': 1, 'name': 'Cookie', 'show_on_menu': true, 'tags': [], 'has_photo': false, 'priced': true, 'price': 9, 'photo_v': null, 'show_ingredients': false},
            ]),
        'GET /api/combos': (_) => (200, page),
        'POST /api/combos': (_) => (201, page),
      });
      final s = be.session(store: MemoryTokenStore()..write('owner_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/painel/cardapio', session: s);
      await t.pumpAndSettle();

      // ── 1. Abre o editor.
      await t.scrollUntilVisible(find.byKey(const Key('combo_new')), 300, scrollable: find.byType(Scrollable).first);
      await t.tap(find.byKey(const Key('combo_new')));
      await t.pumpAndSettle();

      // ── 2. 2 cookies + 1 brownie: avulso R\$ 30, sugerido R\$ 27.
      await t.tap(find.byKey(const Key('combo_add_1')));
      await t.tap(find.byKey(const Key('combo_add_1')));
      await t.tap(find.byKey(const Key('combo_add_2')));
      await t.pump();
      expect(find.textContaining('27,00'), findsWidgets);

      // ── 3. Nome e salvar.
      await t.enterText(find.byKey(const Key('combo_name')), 'Combo Festa');
      await t.ensureVisible(find.byKey(const Key('combo_save')));
      await t.tap(find.byKey(const Key('combo_save')));
      await t.pumpAndSettle();
      final body = be.bodies['POST /api/combos'] as Map;
      expect(body['name'], 'Combo Festa');
      expect(body['price'], isNull);
      expect(body['items'], [{'recipe_id': 1, 'qty': 2}, {'recipe_id': 2, 'qty': 1}]);
    });
  });
}
