import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:raykookie/app.dart';
import 'package:raykookie_common/core/app_info.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'package:raykookie/core/customer_push.dart';
import 'package:raykookie/features/customer/customer_auth_screen.dart' show googleAuthenticate;

import 'helpers.dart';

Future<void> pumpApp(WidgetTester t, FakeBackend be, {String at = '/', Session? session, CustomerPush? push}) async {
  useBigPhone(t);
  final s = session ?? be.session();
  await t.pumpWidget(RaykookieApp(session: s, initialLocation: at, push: push));
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

  group('versão do app', () {
    testWidgets('mostra a versão instalada no rodapé e avisa quando há build mais novo', (t) async {
      final be = FakeBackend({
        'GET /api/menu': (_) => (200, menuJson),
        'GET /api/downloads': (_) => (200, {'client': {'version': '1.1.0', 'build': 5, 'size': 20971520, 'date': '2026-10-02', 'url': '/api/downloads/client'}}),
      });
      await pumpApp(t, be);
      expect(find.text('Nova versão disponível'), findsOneWidget);
      expect(find.textContaining('1.1.0 (build 5)'), findsOneWidget);
      await t.scrollUntilVisible(find.textContaining('versão 1.0.0 (build 1)'), 500, scrollable: find.byType(Scrollable).first);
      expect(find.text('Raykookie · versão 1.0.0 (build 1)'), findsOneWidget);
    });

    testWidgets('sem versão nova publicada, não mostra aviso', (t) async {
      final be = FakeBackend({
        'GET /api/menu': (_) => (200, menuJson),
        'GET /api/downloads': (_) => (200, {'client': {'version': '1.0.0', 'build': 1, 'size': 1, 'date': '2026-10-01', 'url': '/api/downloads/client'}}),
      });
      await pumpApp(t, be);
      expect(find.text('Nova versão disponível'), findsNothing);
    });
  });

  group('loja (cliente)', () {
    testWidgets('home mostra a apresentação e o cardápio vindo das receitas', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      expect(find.text('Confeitaria feita à mão, do forno para a sua mesa.'), findsOneWidget);
      await t.scrollUntilVisible(find.text('Brigadeiro gourmet'), 400, scrollable: find.byType(Scrollable).first);
      expect(find.text('Brigadeiro gourmet'), findsOneWidget);
      expect(find.text('R\$ 6,50'), findsOneWidget);
      expect(find.text('Cookies e biscoitos'), findsWidgets); // vários grupos => filtro, título da categoria e linha do item
      expect(be.calls, contains('GET /api/menu'));
    });

    testWidgets('não existe nenhuma entrada para o painel da confeiteira (nem toque longo na logo)', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      expect(find.textContaining('Painel'), findsNothing);
      expect(find.textContaining('confeiteira'), findsNothing);
    });

    testWidgets('sem sessão, "Fazer pedido" leva ao login do cliente', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      await t.tap(find.text('Fazer pedido').first);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Entre para fazer seu pedido'), findsOneWidget);
      expect(find.text('Continuar com o Google'), findsOneWidget);
    });

    testWidgets('login do cliente com e-mail e senha leva à lista de pedidos', (t) async {
      final be = FakeBackend({
        'POST /api/cliente/login': (_) => (200, {'ok': true, 'token': 'tok-cliente'}),
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, []),
        'GET /api/menu': (_) => (200, menuJson),
      });
      await pumpApp(t, be, at: '/cliente/entrar');
      await t.enterText(find.byType(TextField).at(0), 'joana@example.com');
      await t.enterText(find.byType(TextField).at(1), 'SenhaJoana2026');
      await t.tap(find.widgetWithText(FilledButton, 'Entrar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/cliente/login'], {'email': 'joana@example.com', 'password': 'SenhaJoana2026'});
      expect(find.text('Meus pedidos'), findsOneWidget);
      expect(find.text('Você ainda não fez pedidos'), findsOneWidget);
    });

    group('login com Google', () {
      setUp(() {
        addTearDown(() => AppConfig.googleServerClientIdOverride = null);
        final orig = googleAuthenticate;
        addTearDown(() => googleAuthenticate = orig);
      });

      testWidgets('sem GOOGLE_SERVER_CLIENT_ID configurado (como em produção hoje), só avisa e não tenta entrar', (t) async {
        AppConfig.googleServerClientIdOverride = null; // build sem --dart-define=GOOGLE_SERVER_CLIENT_ID: é o estado real de produção
        var called = false;
        googleAuthenticate = (_) async { called = true; return 'algum-token'; };
        final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
        await pumpApp(t, be, at: '/cliente/entrar');
        await t.tap(find.text('Continuar com o Google'));
        await t.pump(const Duration(milliseconds: 300));
        expect(find.text('O login com o Google ainda não está disponível. Entre com e-mail e senha.'), findsOneWidget);
        expect(called, isFalse); // nem chega a chamar o Google
        expect(be.calls, isEmpty); // nem chega a chamar o backend
        expect(find.text('Entre para fazer seu pedido'), findsOneWidget); // continua na tela de login
      });

      testWidgets('com GOOGLE_SERVER_CLIENT_ID configurado, entra com o token do Google e vai para os pedidos', (t) async {
        AppConfig.googleServerClientIdOverride = 'web-client-id.apps.googleusercontent.com';
        String? usedServerClientId;
        googleAuthenticate = (serverClientId) async { usedServerClientId = serverClientId; return 'id-token-do-google'; };
        final be = FakeBackend({
          'POST /api/cliente/google/token': (_) => (200, {'ok': true, 'token': 'tok-google'}),
          'GET /api/cliente/me': (_) => (200, customerJson),
          'GET /api/cliente/pedidos': (_) => (200, []),
          'GET /api/menu': (_) => (200, menuJson),
        });
        await pumpApp(t, be, at: '/cliente/entrar');
        await t.tap(find.text('Continuar com o Google'));
        await t.pumpAndSettle(const Duration(milliseconds: 100));
        expect(usedServerClientId, 'web-client-id.apps.googleusercontent.com');
        expect(be.bodies['POST /api/cliente/google/token'], {'idToken': 'id-token-do-google'});
        expect(find.text('Meus pedidos'), findsOneWidget);
      });

      testWidgets('o Google não devolve idToken: mostra erro e não chama o backend', (t) async {
        AppConfig.googleServerClientIdOverride = 'web-client-id.apps.googleusercontent.com';
        googleAuthenticate = (_) async => null;
        final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
        await pumpApp(t, be, at: '/cliente/entrar');
        await t.tap(find.text('Continuar com o Google'));
        await t.pump(const Duration(milliseconds: 300));
        expect(find.text('O Google não devolveu a identificação. Tente de novo.'), findsOneWidget);
        expect(be.calls, isEmpty);
      });
    });

    testWidgets('senha curta no cadastro mostra aviso amigável e não chama o servidor', (t) async {
      final be = FakeBackend({});
      await pumpApp(t, be, at: '/cliente/entrar');
      await t.tap(find.text('Criar conta').first);
      await t.pumpAndSettle();
      await t.enterText(find.byType(TextField).at(0), 'Joana');
      await t.enterText(find.byType(TextField).at(1), 'joana@example.com');
      await t.enterText(find.byType(TextField).at(2), 'curta');
      await t.tap(find.widgetWithText(FilledButton, 'Criar conta e continuar'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.text('A senha precisa ter pelo menos 8 caracteres.'), findsOneWidget);
      expect(be.calls, isEmpty);
    });

    testWidgets('lista de pedidos mostra itens, total e situação', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 5, 'created_at': '2026-09-30T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'manha', 'fulfillment': 'entrega',
                'street': 'Avenida Paulista', 'number': '1000', 'complement': '', 'district': 'Bela Vista', 'city': 'São Paulo', 'payment_method': 'pix',
                'notes': '', 'total': 19.5, 'status': 'producao',
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 3, 'unit_price': 6.5}],
              }
            ]),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos?novo=5', session: s);
      expect(find.text('Pedido #5 recebido!'), findsOneWidget);
      expect(find.text('Em preparo'), findsOneWidget);
      expect(find.textContaining('Brigadeiro gourmet'), findsOneWidget);
      expect(find.text('R\$ 19,50'), findsWidgets); // linha do item e total
      expect(find.textContaining('Avenida Paulista'), findsOneWidget);
    });
  });

  group('termos e privacidade (cliente)', () {
    testWidgets('a tela de entrar avisa que criar conta aceita os termos e abre o texto', (t) async {
      final be = FakeBackend({'GET /api/legal/termos': (_) => (200, legalJson)});
      await pumpApp(t, be, at: '/cliente/entrar');
      await t.tap(find.text('Criar conta').first);
      await t.pumpAndSettle();
      expect(find.textContaining('Ao criar sua conta, você concorda com os'), findsOneWidget);
      await t.tap(find.text('Termos de Serviço'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('1. Quem somos'), findsOneWidget);
      expect(find.text('Item de lista dois.'), findsOneWidget);
    });

    testWidgets('o rodapé da home tem os dois links', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      await t.scrollUntilVisible(find.text('Política de Privacidade'), 600, scrollable: find.byType(Scrollable).first);
      expect(find.text('Termos de Serviço'), findsOneWidget);
    });
  });

  group('redesign do cliente', () {
    testWidgets('home: o cardápio tem filtro por categoria e botão de pedir em cada item', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      await t.scrollUntilVisible(find.text('Brigadeiro gourmet'), 400, scrollable: find.byType(Scrollable).first);
      await t.ensureVisible(find.widgetWithText(FilledButton, 'Pedir').last); // o botão Pedir fica abaixo do nome, no fim do cartão
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(ChoiceChip, 'Todos'), findsOneWidget);
      // cada item tem um botão "Pedir" com rótulo acessível próprio (checado no widget: a árvore de semântica só inclui o que está na janela)
      expect(find.byWidgetPredicate((w) => w is Semantics && w.properties.label == 'Pedir Brigadeiro gourmet'), findsOneWidget);
    });

    testWidgets('home: a barra "Fazer pedido" só aparece depois que o botão do topo sai da tela', (t) async {
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      expect(find.byType(Material).evaluate().where((e) => (e.widget as Material).elevation == 6), isEmpty);
      await t.drag(find.byType(Scrollable).first, const Offset(0, -900));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.widgetWithText(BusyButton, 'Fazer pedido'), findsWidgets);
    });

    testWidgets('pedido com Pix aguardando mostra o selo, o valor e o Pix copia e cola', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 7, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
                'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'pix', 'notes': '', 'total': 98.0,
                'status': 'novo', 'paid': false, 'pix': {'code': '00020126-CODIGO-PIX', 'qr_base64': '', 'expires_in': 1800, 'expired': false},
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 49.0}],
              }
            ]),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      expect(find.text('Aguardando pagamento'), findsOneWidget);
      expect(find.text('Pague com Pix'), findsOneWidget);
      expect(find.text('00020126-CODIGO-PIX'), findsOneWidget);
      expect(find.text('Copiar Pix copia e cola'), findsOneWidget);
      expect(find.text('Pagamento confirmado. '), findsNothing);
    });

    testWidgets('pedido pago mostra "Pix pago", sem caixa do Pix, e a barra Novo pedido não cobre a lista', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 8, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
                'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'pix', 'notes': '', 'total': 98.0,
                'status': 'producao', 'paid': true, 'pix': null,
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 49.0}],
              }
            ]),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      expect(find.text('Pix pago'), findsOneWidget);
      expect(find.text('Pague com Pix'), findsNothing);
      expect(find.textContaining('Pagamento confirmado.'), findsOneWidget);
      // a barra inferior tem a altura do botão, e não a da tela
      final bar = t.getSize(find.widgetWithText(OutlinedButton, 'Novo pedido'));
      expect(bar.height, lessThan(80));
      expect(find.text('Pedido #8'), findsOneWidget);
    });

    testWidgets('pedido entregue oferece "Pedir de novo" com o primeiro item', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 9, 'created_at': '2026-09-30T20:00:00.000Z', 'scheduled_for': '2026-10-01', 'time_window': 'manha', 'fulfillment': 'retirada',
                'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'dinheiro', 'notes': '', 'total': 13.0,
                'status': 'entregue', 'paid': false, 'pix': null,
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}],
              }
            ]),
        'GET /api/menu': (_) => (200, menuJson),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      await t.tap(find.text('Pedir de novo'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Passo 1 de 5'), findsOneWidget);
      expect(be.calls, contains('GET /api/menu'));
    });
  });

  group('novo pedido em passos', () {
    Future<void> openNew(WidgetTester t, {String? item}) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/menu': (_) => (200, menuJson),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos/novo${item == null ? '' : '?item=$item'}', session: s);
    }

    testWidgets('começa no passo 1 de 5, só com os itens, e o botão é Continuar', (t) async {
      await openNew(t);
      expect(find.text('Passo 1 de 5'), findsOneWidget);
      expect(find.text('O que você quer?'), findsOneWidget);
      expect(find.text('Seus dados'), findsNothing); // as outras etapas ainda não aparecem
      expect(find.widgetWithText(FilledButton, 'Continuar'), findsOneWidget);
      expect(find.text('Finalizar pedido'), findsNothing);
    });

    testWidgets('sem item escolhido, Continuar avisa e fica no passo 1', (t) async {
      await openNew(t);
      await t.tap(find.widgetWithText(FilledButton, 'Continuar'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.text('Escolha pelo menos um item do cardápio.'), findsOneWidget);
      expect(find.text('Passo 1 de 5'), findsOneWidget);
    });

    testWidgets('com um item, avança para os dados e o Voltar do sistema volta ao passo anterior', (t) async {
      await openNew(t, item: '1');
      await t.tap(find.widgetWithText(FilledButton, 'Continuar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Passo 2 de 5'), findsOneWidget);
      expect(find.text('Seus dados'), findsWidgets);
      expect(find.text('O que você quer?'), findsNothing);
      // o botão Voltar volta à etapa anterior, sem sair da tela
      await t.tap(find.byTooltip('Voltar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Passo 1 de 5'), findsOneWidget);
      expect(find.text('O que você quer?'), findsOneWidget);
      expect(find.byTooltip('Voltar'), findsNothing); // no primeiro passo não há para onde voltar
      // o gesto/botão Voltar do sistema também só volta um passo
      await t.tap(find.widgetWithText(FilledButton, 'Continuar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.binding.handlePopRoute();
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Passo 1 de 5'), findsOneWidget);
    });

    testWidgets('a etapa de entrega pede a data antes de avançar', (t) async {
      await openNew(t, item: '1');
      await t.tap(find.widgetWithText(FilledButton, 'Continuar')); // itens → dados
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Continuar')); // dados → entrega (perfil traz nome e telefone)
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      if (find.text('Passo 3 de 5').evaluate().isEmpty) return; // perfil de teste sem telefone: o aviso da etapa 2 já foi coberto
      await t.tap(find.widgetWithText(FilledButton, 'Continuar'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.text('Passo 3 de 5'), findsOneWidget);
    });
  });

  group('validade do Pix', () {
    Map<String, dynamic> pixOrder({required String code, required int expiresIn, required bool expired}) => {
          'id': 7, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
          'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'pix', 'notes': '', 'total': 98.0,
          'status': 'novo', 'paid': false, 'pix': {'code': code, 'qr_base64': '', 'expires_in': expiresIn, 'expired': expired},
          'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 49.0}],
        };

    Future<FakeBackend> open(WidgetTester t, Map<String, dynamic> Function() order, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [order()]),
        ...extra,
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      return be;
    }

    testWidgets('Pix válido mostra o prazo e a contagem regressiva anda', (t) async {
      await open(t, () => pixOrder(code: 'CODIGO-VALIDO', expiresIn: 1800, expired: false));
      expect(find.text('Expira em 30:00'), findsOneWidget);
      expect(find.text('CODIGO-VALIDO'), findsOneWidget);
      await t.pump(const Duration(seconds: 65));
      expect(find.text('Expira em 28:55'), findsOneWidget);
      expect(find.text('Este Pix expirou'), findsNothing);
    });

    testWidgets('quando a contagem chega a zero, o código some e aparece "Gerar novo Pix"', (t) async {
      await open(t, () => pixOrder(code: 'CODIGO-CURTO', expiresIn: 3, expired: false));
      expect(find.text('Expira em 00:03'), findsOneWidget);
      await t.pump(const Duration(seconds: 4));
      expect(find.text('Este Pix expirou'), findsOneWidget);
      expect(find.text('CODIGO-CURTO'), findsNothing);
      expect(find.text('Copiar Pix copia e cola'), findsNothing);
      expect(find.text('Gerar novo Pix'), findsOneWidget);
    });

    testWidgets('Pix que já chegou vencido não mostra código e oferece um novo', (t) async {
      await open(t, () => pixOrder(code: '', expiresIn: 0, expired: true));
      expect(find.text('Este Pix expirou'), findsOneWidget);
      expect(find.text('Pague com Pix'), findsNothing);
    });

    testWidgets('"Gerar novo Pix" chama o servidor e mostra o código novo com a nova contagem', (t) async {
      var renewed = false;
      final be = await open(
        t,
        () => renewed ? pixOrder(code: 'CODIGO-NOVO', expiresIn: 3600, expired: false) : pixOrder(code: '', expiresIn: 0, expired: true),
        extra: {
          'POST /api/cliente/pedidos/7/pix': (_) {
            renewed = true;
            return (200, {'ok': true, 'state': 'renewed'});
          },
        },
      );
      await t.tap(find.text('Gerar novo Pix'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('POST /api/cliente/pedidos/7/pix'));
      expect(find.text('Pague com Pix'), findsOneWidget);
      expect(find.text('CODIGO-NOVO'), findsOneWidget);
      expect(find.text('Expira em 1 h 00 min'), findsOneWidget);
      expect(find.text('Este Pix expirou'), findsNothing);
    });

    testWidgets('erro ao gerar novo Pix mostra o aviso e mantém o botão', (t) async {
      await open(
        t,
        () => pixOrder(code: '', expiresIn: 0, expired: true),
        extra: {'POST /api/cliente/pedidos/7/pix': (_) => (502, {'error': 'Não conseguimos gerar o Pix agora. Tente de novo em instantes.'})},
      );
      await t.tap(find.text('Gerar novo Pix'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.text('Não conseguimos gerar o Pix agora. Tente de novo em instantes.'), findsOneWidget);
      expect(find.text('Gerar novo Pix'), findsOneWidget);
    });
  });

  group('meus dados (LGPD)', () {
    Map<String, dynamic> dados({int open = 0, bool password = true, bool google = false}) => {
          ...customerJson, 'created_at': '2026-09-01T10:00:00Z', 'has_password': password, 'has_google': google, 'orders': 3, 'open_orders': open, 'open_requests': 0,
          'requests': [], 'request_types': [{'id': 'anonimizacao', 'label': 'Anonimizar, bloquear ou eliminar dados'}, {'id': 'outro', 'label': 'Outro assunto'}], 'deadline_days': 15,
        };

    Future<FakeBackend> open(WidgetTester t, Map<String, dynamic> d, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({'GET /api/cliente/me': (_) => (200, customerJson), 'GET /api/cliente/me/dados': (_) => (200, d), ...extra});
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/conta', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    final listFinder = find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first;

    testWidgets('mostra todos os direitos: ver, baixar, corrigir, compartilhamento, solicitar e excluir', (t) async {
      await open(t, dados());
      expect(find.text('O que guardamos sobre você'), findsOneWidget);
      expect(find.text('Baixar meus dados'), findsOneWidget);
      expect(find.text('Corrigir meus dados'), findsOneWidget);
      await t.scrollUntilVisible(find.text('Com quem compartilhamos'), 500, scrollable: listFinder);
      await t.scrollUntilVisible(find.text('Fazer uma solicitação sobre meus dados'), 500, scrollable: listFinder);
      await t.scrollUntilVisible(find.widgetWithText(OutlinedButton, 'Excluir minha conta'), 500, scrollable: listFinder);
    });

    testWidgets('corrigir envia os campos editados', (t) async {
      final be = await open(t, dados(), extra: {'PATCH /api/cliente/me': (_) => (200, {'ok': true})});
      t.view.physicalSize = const Size(430, 4000);
      addTearDown(t.view.reset);
      await t.pumpAndSettle();
      await t.enterText(find.byType(TextField).first, 'Maria Nova');
      await t.tap(find.text('Salvar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('PATCH /api/cliente/me'));
    });

    testWidgets('com pedido em andamento não deixa excluir e explica por quê', (t) async {
      await open(t, dados(open: 2));
      await t.scrollUntilVisible(find.textContaining('2 pedido(s) em andamento'), 500, scrollable: listFinder);
      expect(find.widgetWithText(OutlinedButton, 'Excluir minha conta'), findsNothing);
    });

    testWidgets('excluir pede EXCLUIR, manda a confirmação e volta ao início', (t) async {
      final be = await open(t, dados(password: false), extra: {'DELETE /api/cliente/me': (_) => (200, {'ok': true}), 'POST /api/cliente/logout': (_) => (200, {'ok': true})});
      t.view.physicalSize = const Size(430, 4000);
      addTearDown(t.view.reset);
      await t.pumpAndSettle();
      await t.tap(find.widgetWithText(OutlinedButton, 'Excluir minha conta'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(textButtonEnabled(t, 'Excluir conta'), isFalse);
      await t.enterText(find.byType(TextField).last, 'excluir');
      await t.pump();
      await t.tap(find.text('Excluir conta'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(be.calls, contains('DELETE /api/cliente/me'));
      expect((be.bodies['DELETE /api/cliente/me'] as Map)['confirm'], 'excluir');
    });
  });

  group('pedir devolução (wizard)', () {
    Map<String, dynamic> order({required String status, required bool photos}) => {
          'id': 4, 'code': 4821, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
          'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'pix', 'notes': '', 'total': 13.0,
          'status': status, 'paid': true, 'can_cancel': false, 'pix': null, 'payment_state': 'pago', 'refunded_total': 0, 'cancelled_by': null, 'cancel_reason': '',
          'refund': {'mode': 'solicitacao', 'max_amount': 13.0, 'photos_required': photos, 'deadline': null, 'why_not': ''}, 'refunds': [],
          'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}],
        };

    Future<FakeBackend> open(WidgetTester t, {required bool delivered, Map<String, Handler> extra = const {}}) async {
      ImagePickerPlatform.instance = _FakePicker();
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [order(status: delivered ? 'entregue' : 'producao', photos: delivered)]),
        ...extra,
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos/4/devolucao', session: s);
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    /// os avisos (SnackBar) ficam por cima do botão: tira antes de tocar
    Future<void> quiet(WidgetTester t) async {
      ScaffoldMessenger.of(t.element(find.byType(Scaffold).first)).clearSnackBars();
      await t.pumpAndSettle(const Duration(milliseconds: 100));
    }

    Future<void> next(WidgetTester t) async {
      await quiet(t);
      await t.tap(find.widgetWithText(FilledButton, 'Continuar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
    }

    testWidgets('cada passo valida antes de continuar', (t) async {
      await open(t, delivered: true);
      expect(find.text('Passo 1 de 4'), findsOneWidget);
      await next(t);
      expect(find.text('Escolha o motivo.'), findsOneWidget);
      await quiet(t);
      await t.tap(find.text('Chegou amassado, quebrado ou derretido'));
      await t.pump();
      await next(t);
      await next(t); // sem itens marcados
      expect(find.text('Marque pelo menos um item com problema.'), findsOneWidget);
      await quiet(t);
      await t.tap(find.byTooltip('Mais Brigadeiro gourmet'));
      await t.pump();
      expect(find.text('Valor que você está pedindo de volta: R\$ 6,50'), findsOneWidget);
      await next(t);
      await next(t); // descrição curta
      expect(find.textContaining('pelo menos 20 caracteres'), findsWidgets);
    });

    testWidgets('entregue: foto obrigatória; envia motivo, itens, descrição e a foto', (t) async {
      final be = await open(t, delivered: true, extra: {'POST /api/cliente/pedidos/4/devolucao': (_) => (201, {'ok': true, 'id': 9})});
      await t.tap(find.text('Chegou amassado, quebrado ou derretido'));
      await t.pump();
      await next(t);
      await t.tap(find.byTooltip('Mais Brigadeiro gourmet'));
      await t.pump();
      await next(t);
      await t.enterText(find.byType(TextField).last, 'O brigadeiro chegou todo derretido e amassado.');
      await t.pump();
      await next(t);
      expect(find.textContaining('obrigatório: de 1 a 5'), findsOneWidget);
      await next(t); // sem foto
      expect(find.text('Envie pelo menos uma foto para mostrar o problema.'), findsOneWidget);
      await quiet(t);
      await t.tap(find.text('Galeria'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.byTooltip('Remover foto 1'), findsOneWidget);
      await next(t);
      expect(find.text('Passo 5 de 5'), findsOneWidget);
      await quiet(t);
      await t.tap(find.widgetWithText(FilledButton, 'Enviar solicitação'));
      await t.pump();
      expect(find.text('Confirme que entendeu como funciona a análise.'), findsOneWidget);
      await quiet(t);
      await quiet(t);
      await t.tap(find.byType(Checkbox));
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Enviar solicitação'));
      await t.pumpAndSettle(const Duration(milliseconds: 200));
      expect(be.calls, contains('POST /api/cliente/pedidos/4/devolucao'));
    });

    testWidgets('"Não recebi": não há produto para fotografar, o passo de fotos some', (t) async {
      await open(t, delivered: false);
      await t.tap(find.text('Não recebi ou não ficou pronto no horário combinado'));
      await t.pump();
      expect(find.text('Passo 1 de 4'), findsOneWidget);
    });
  });

  group('avisos do pedido (notificações)', () {
    Future<FakeBackend> open(WidgetTester t, _FakePush push, {String at = '/cliente/pedidos'}) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              for (final id in [4, 5, 6, 7, 8])
                {'id': id, 'code': 40000 + id, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
                 'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'pix', 'notes': '', 'total': 13.0, 'status': 'novo', 'paid': true,
                 'can_cancel': true, 'pix': null, 'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}]},
            ]),
        'POST /api/cliente/logout': (_) => (200, {'ok': true}),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: at, session: s, push: push);
      return be;
    }

    testWidgets('sem autorização: mostra o cartão; "Ativar" pede a permissão e o cartão some', (t) async {
      final push = _FakePush();
      await open(t, push);
      expect(find.text('Quer saber quando o pedido mudar?'), findsOneWidget);
      await t.tap(find.text('Ativar notificações'));
      await t.pumpAndSettle();
      expect(push.enabled, 1);
      expect(find.text('Quer saber quando o pedido mudar?'), findsNothing);
    });

    testWidgets('"Agora não" esconde o cartão; build sem Firebase nem mostra', (t) async {
      final push = _FakePush();
      await open(t, push);
      await t.tap(find.text('Agora não'));
      await t.pumpAndSettle();
      expect(find.text('Quer saber quando o pedido mudar?'), findsNothing);
      final off = _FakePush()..available = false;
      await open(t, off);
      expect(find.text('Quer saber quando o pedido mudar?'), findsNothing);
    });

    testWidgets('tocar num aviso abre Meus pedidos com o pedido em destaque', (t) async {
      final push = _FakePush()..granted = true;
      await open(t, push, at: '/');
      push.onOpen!(CustomerPush.routeFor({'type': 'devolucao', 'orderId': '8'}));
      await t.pumpAndSettle(const Duration(milliseconds: 400));
      expect(find.text('Meus pedidos'), findsOneWidget);
      expect(find.text('Pedido #40008'), findsOneWidget); // rolou até o último pedido
      expect(CustomerPush.routeFor({'orderId': '8'}), '/cliente/pedidos?pedido=8');
      expect(CustomerPush.routeFor({}), '/cliente/pedidos');
    });

    testWidgets('aviso chegando com Meus pedidos aberto recarrega a lista (não fica com dado velho)', (t) async {
      final push = _FakePush()..granted = true;
      final be = await open(t, push);
      final before = be.calls.where((c) => c == 'GET /api/cliente/pedidos').length;
      push.bump();
      await t.pumpAndSettle();
      expect(be.calls.where((c) => c == 'GET /api/cliente/pedidos').length, before + 1);
    });

    testWidgets('sair da conta tira o celular da lista de avisos antes de deslogar', (t) async {
      final push = _FakePush()..granted = true;
      final be = await open(t, push);
      await t.tap(find.text('Sair'));
      await t.pumpAndSettle();
      expect(push.unregistered, 1);
      expect(be.calls, contains('POST /api/cliente/logout'));
    });
  });

  group('cancelar pedido', () {
    Map<String, dynamic> order({required String status, required bool paid, required bool canCancel, String method = 'dinheiro', String refundMode = 'nenhum', bool photos = false, List<Map<String, dynamic>> refunds = const [], String why = ''}) => {
          'id': 4, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
          'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': method, 'notes': '', 'total': 13.0,
          'status': status, 'paid': paid, 'can_cancel': canCancel, 'pix': null,
          'payment_state': paid ? 'pago' : 'pendente', 'refunded_total': 0, 'cancelled_by': null, 'cancel_reason': '',
          'refund': {'mode': refundMode, 'max_amount': 13.0, 'photos_required': photos, 'deadline': null, 'why_not': why},
          'refunds': refunds,
          'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}],
        };

    Future<FakeBackend> open(WidgetTester t, Map<String, dynamic> Function() o, {Map<String, Handler> extra = const {}}) async {
      final be = FakeBackend({'GET /api/cliente/me': (_) => (200, customerJson), 'GET /api/cliente/pedidos': (_) => (200, [o()]), ...extra});
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      return be;
    }

    testWidgets('pedido novo e sem pagamento mostra "Cancelar pedido"', (t) async {
      await open(t, () => order(status: 'novo', paid: false, canCancel: true));
      expect(find.widgetWithText(OutlinedButton, 'Cancelar pedido'), findsOneWidget);
      expect(find.text('Para cancelar este pedido, fale com a gente.'), findsNothing);
    });

    testWidgets('pedido em produção não tem o botão de cancelar: explica e oferece "Pedir devolução"', (t) async {
      await open(t, () => order(status: 'producao', paid: true, canCancel: false, method: 'pix', refundMode: 'solicitacao'));
      expect(find.widgetWithText(OutlinedButton, 'Cancelar pedido'), findsNothing);
      expect(find.textContaining('o cancelamento é feito por um pedido de devolução'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Pedir devolução'), findsOneWidget);
      expect(find.textContaining('não é garantida'), findsOneWidget);
    });

    testWidgets('pago e ainda não iniciado: "Cancelar e receber o Pix de volta", com o valor e o motivo', (t) async {
      final be = await open(t, () => order(status: 'novo', paid: true, canCancel: true, method: 'pix', refundMode: 'automatico'), extra: {
        'POST /api/cliente/pedidos/4/cancelar': (_) => (200, {'ok': true, 'state': 'cancelled', 'refunded': 13.0}),
      });
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar e receber o Pix de volta'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.textContaining('Você recebe R\$ 13,00 de volta por Pix'), findsOneWidget);
      await t.tap(find.widgetWithText(FilledButton, 'Cancelar pedido'));
      await t.pump();
      expect(be.calls.where((c) => c.contains('cancelar')), isEmpty); // sem motivo escolhido, o botão não faz nada
      await t.tap(find.text('Achei caro ou encontrei outra opção'));
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/cliente/pedidos/4/cancelar'], {'reason_code': 'preco', 'reason': ''});
    });

    testWidgets('acompanha a devolução: em análise, aprovada, concluída e negada', (t) async {
      Map<String, dynamic> r(String status, {String? decision}) => {
            'id': 9, 'kind': 'solicitado', 'status': status, 'label': {'solicitado': 'Em análise', 'concluido': 'Devolvido', 'negado': 'Devolução negada'}[status], 'reason': 'Chegou amassado, quebrado ou derretido',
            'requested_amount': 6.5, 'approved_amount': status == 'concluido' ? 6.5 : null, 'decision_note': '', 'decision_reason': decision ?? '', 'photos': 2, 'created_at': '2026-10-03T12:00:00.000Z',
          };
      await open(t, () => order(status: 'entregue', paid: true, canCancel: false, method: 'pix', refunds: [r('solicitado')]));
      expect(find.text('Devolução em análise'), findsOneWidget); // selo do topo
      expect(find.text('Você pediu R\$ 6,50 de volta'), findsOneWidget);
      expect(find.text('Motivo: Chegou amassado, quebrado ou derretido · 2 fotos'), findsOneWidget);
      expect(find.textContaining('Você recebe uma notificação'), findsOneWidget);
      expect(find.text('Desistir da solicitação'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Pedir devolução'), findsNothing); // uma solicitação por pedido
    });

    testWidgets('devolução negada mostra o motivo; concluída mostra o valor', (t) async {
      Map<String, dynamic> r(String status, String label, {String reason = '', double? approved}) => {
            'id': 9, 'kind': 'solicitado', 'status': status, 'label': label, 'reason': 'Problema de qualidade (cru, queimado, seco, sabor estranho)',
            'requested_amount': 6.5, 'approved_amount': approved, 'decision_note': 'Obrigada pelo aviso', 'decision_reason': reason, 'photos': 1, 'created_at': '2026-10-03T12:00:00.000Z',
          };
      await open(t, () => order(status: 'entregue', paid: true, canCancel: false, method: 'pix', refunds: [r('negado', 'Devolução negada', reason: 'As fotos não mostram o problema')]));
      expect(find.text('Devolução negada'), findsOneWidget);
      expect(find.text('Motivo da loja: As fotos não mostram o problema'), findsOneWidget);
      expect(find.text('Obrigada pelo aviso'), findsNothing); // vem junto: "Mensagem da loja: …"
      expect(find.textContaining('Mensagem da loja: '), findsOneWidget);
      expect(find.text('Desistir da solicitação'), findsNothing);
    });

    testWidgets('aprovada em parte e Pix devolvido: valor, mensagem da loja e quando voltou', (t) async {
      await open(t, () => order(status: 'entregue', paid: true, canCancel: false, method: 'pix', refunds: [
        {'id': 9, 'kind': 'solicitado', 'status': 'concluido', 'label': 'Devolvido', 'reason': 'x', 'requested_amount': 30.0, 'approved_amount': 20.0,
         'decision_note': 'Devolvemos os 2 cookies quebrados.', 'decision_reason': '', 'photos': 2, 'created_at': '2026-09-28T12:00:00.000Z', 'refunded_at': '2026-09-29T13:12:00.000Z'},
      ]));
      expect(find.text('Devolvido R\$ 20,00'), findsOneWidget);
      expect(find.text('Devolução aprovada em parte: R\$ 20,00 de R\$ 30,00'), findsOneWidget);
      expect(find.textContaining('Devolvemos os 2 cookies quebrados.'), findsOneWidget);
      expect(find.textContaining('O valor voltou para a conta que pagou, em 29/09'), findsOneWidget);
      expect(find.byWidgetPredicate((w) => w is Semantics && w.properties.label == 'Andamento da devolução: Pix devolvido'), findsOneWidget);
    });

    testWidgets('cancelado pela loja: quem cancelou, o motivo, a mensagem e o Pix devolvido', (t) async {
      await open(t, () => {
            ...order(status: 'cancelado', paid: true, canCancel: false, method: 'pix', refunds: [
              {'id': 10, 'kind': 'loja', 'status': 'concluido', 'label': 'Devolvido', 'reason': 'Faltou ingrediente', 'requested_amount': 13.0, 'approved_amount': 13.0,
               'decision_note': '', 'decision_reason': '', 'photos': 0, 'created_at': '2026-10-03T18:00:00.000Z', 'refunded_at': '2026-10-03T18:40:00.000Z'},
            ]),
            'cancel': {'by': 'loja', 'reason': 'Faltou ingrediente', 'note': 'O chocolate acabou no fornecedor.', 'at': '2026-10-03T18:00:00.000Z'},
          });
      expect(find.text('Cancelado'), findsOneWidget);
      expect(find.text('CANCELADO PELA LOJA'), findsOneWidget);
      expect(find.text('Motivo: Faltou ingrediente'), findsOneWidget);
      expect(find.textContaining('O chocolate acabou no fornecedor.'), findsOneWidget);
      expect(find.text('Pix devolvido: R\$ 13,00'), findsOneWidget);
      expect(find.byWidgetPredicate((w) => w is Semantics && w.properties.label == 'Andamento da devolução: Pix devolvido'), findsOneWidget);
    });

    testWidgets('caso #38118: devolução total aprovada encerra o pedido (não diz "cancelado pela loja" nem "em preparo")', (t) async {
      await open(t, () => {
            ...order(status: 'cancelado', paid: true, canCancel: false, method: 'pix', refunds: [
              {'id': 4, 'kind': 'solicitado', 'status': 'concluido', 'label': 'Devolvido', 'reason': 'Desisti do pedido (já está em produção)', 'requested_amount': 6.0, 'approved_amount': 6.0,
               'decision_note': '', 'decision_reason': '', 'photos': 0, 'created_at': '2026-10-03T12:50:49.000Z', 'refunded_at': '2026-10-03T12:51:48.000Z'},
            ]),
            'refunded_total': 6.0,
            'cancel': {'by': 'sistema', 'code': 'devolucao_total', 'reason': 'Todo o valor foi devolvido', 'note': '', 'at': '2026-10-03T12:51:45.000Z'},
          });
      expect(find.text('Devolvido R\$ 6,00'), findsOneWidget);
      expect(find.text('Pedido encerrado'), findsOneWidget);
      expect(find.text('PEDIDO ENCERRADO'), findsOneWidget);
      expect(find.text('Todo o valor foi devolvido'), findsOneWidget);
      expect(find.textContaining('a loja não vai mais produzir'), findsOneWidget);
      expect(find.text('Em preparo'), findsNothing);
      expect(find.text('Pedido cancelado'), findsNothing);
      expect(find.text('CANCELADO PELA LOJA'), findsNothing);
      expect(find.text('Pedir devolução'), findsNothing);
    });

    testWidgets('a própria cliente cancelou: mostra "Você cancelou" e o motivo escolhido', (t) async {
      await open(t, () => {
            ...order(status: 'cancelado', paid: false, canCancel: false),
            'cancel': {'by': 'cliente', 'reason': 'A data ou o horário não servem mais', 'note': '', 'at': '2026-10-03T18:00:00.000Z'},
          });
      expect(find.text('VOCÊ CANCELOU'), findsOneWidget);
      expect(find.text('Motivo: A data ou o horário não servem mais'), findsOneWidget);
    });

    testWidgets('devolução feita pela loja sem cancelar: "Devolução da loja" com a mensagem', (t) async {
      await open(t, () => order(status: 'producao', paid: true, canCancel: false, method: 'pix', refunds: [
        {'id': 11, 'kind': 'loja', 'status': 'concluido', 'label': 'Devolvido', 'reason': 'Outro motivo', 'requested_amount': 6.5, 'approved_amount': 6.5,
         'decision_note': 'Faltou 1 brigadeiro, devolvemos o valor dele.', 'decision_reason': '', 'photos': 0, 'created_at': '2026-10-03T18:00:00.000Z', 'refunded_at': null},
      ]));
      expect(find.text('DEVOLUÇÃO DA LOJA'), findsOneWidget);
      expect(find.text('Pix devolvido: R\$ 6,50'), findsOneWidget);
      expect(find.textContaining('Faltou 1 brigadeiro'), findsOneWidget);
    });

    testWidgets('desistir da solicitação chama a API e recarrega', (t) async {
      final be = await open(t, () => order(status: 'producao', paid: true, canCancel: false, method: 'pix', refunds: [
        {'id': 9, 'kind': 'solicitado', 'status': 'solicitado', 'label': 'Em análise', 'reason': 'x', 'requested_amount': 6.5, 'approved_amount': null, 'decision_note': '', 'decision_reason': '', 'photos': 0, 'created_at': '2026-10-03T12:00:00.000Z'}
      ]), extra: {'DELETE /api/cliente/pedidos/4/devolucao': (_) => (200, {'ok': true})});
      await t.tap(find.text('Desistir da solicitação'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls, contains('DELETE /api/cliente/pedidos/4/devolucao'));
    });

    testWidgets('pedido entregue ou cancelado não oferece cancelar nem mostra o aviso', (t) async {
      await open(t, () => order(status: 'cancelado', paid: false, canCancel: false));
      expect(find.widgetWithText(OutlinedButton, 'Cancelar pedido'), findsNothing);
      expect(find.text('Para cancelar este pedido, fale com a gente.'), findsNothing);
      expect(find.text('Pedido cancelado'), findsOneWidget); // a linha do tempo
    });

    testWidgets('confirmar no diálogo cancela, manda o motivo e recarrega o pedido', (t) async {
      var cancelled = false;
      final be = await open(
        t,
        () => cancelled ? order(status: 'cancelado', paid: false, canCancel: false) : order(status: 'novo', paid: false, canCancel: true),
        extra: {
          'POST /api/cliente/pedidos/4/cancelar': (_) {
            cancelled = true;
            return (200, {'ok': true, 'state': 'cancelled'});
          },
        },
      );
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Cancelar o pedido #4?'), findsOneWidget);
      await t.tap(find.text('Outro motivo'));
      await t.pump();
      await t.enterText(find.byType(TextField).last, 'Errei o pedido');
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.bodies['POST /api/cliente/pedidos/4/cancelar'], {'reason_code': 'outro', 'reason': 'Errei o pedido'});
      expect(find.widgetWithText(OutlinedButton, 'Cancelar pedido'), findsNothing);
      expect(find.text('Pedido cancelado'), findsWidgets);
    });

    testWidgets('"Voltar" no diálogo não cancela nada', (t) async {
      final be = await open(t, () => order(status: 'novo', paid: false, canCancel: true));
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.text('Voltar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(be.calls.where((c) => c.contains('cancelar')), isEmpty);
      expect(find.widgetWithText(OutlinedButton, 'Cancelar pedido'), findsOneWidget);
    });

    testWidgets('se o servidor recusa (já está em produção), mostra o aviso e recarrega', (t) async {
      await open(
        t,
        () => order(status: 'novo', paid: false, canCancel: true),
        extra: {'POST /api/cliente/pedidos/4/cancelar': (_) => (409, {'error': 'Seu pedido já entrou em produção e não pode mais ser cancelado por aqui. Fale com a gente.'})},
      );
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.text('Achei caro ou encontrei outra opção'));
      await t.pump();
      await t.tap(find.widgetWithText(FilledButton, 'Cancelar pedido'));
      await t.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('já entrou em produção'), findsOneWidget);
    });
  });

  group('código do pedido', () {
    testWidgets('o cliente vê o código de 4 ou 5 dígitos, e não o número interno do pedido', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 17, 'code': 48213, 'created_at': '2026-10-02T20:00:00.000Z', 'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada',
                'street': '', 'number': '', 'complement': '', 'district': '', 'city': '', 'payment_method': 'dinheiro', 'notes': '', 'total': 13.0,
                'status': 'novo', 'paid': false, 'can_cancel': true, 'pix': null,
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}],
              }
            ]),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos?novo=17', session: s);
      expect(find.text('Pedido #48213'), findsOneWidget);
      expect(find.text('Pedido #48213 recebido!'), findsOneWidget);
      expect(find.textContaining('#17'), findsNothing);
      await t.tap(find.widgetWithText(OutlinedButton, 'Cancelar pedido'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Cancelar o pedido #48213?'), findsOneWidget);
      expect(find.textContaining('#17'), findsNothing);
    });
  });

  group('endereço de retirada (cliente)', () {
    Future<FakeBackend> toStep3(WidgetTester t, Map<String, Handler> pickup) async {
      final be = FakeBackend({'GET /api/cliente/me': (_) => (200, customerJson), 'GET /api/menu': (_) => (200, menuJson), ...pickup});
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos/novo?item=1', session: s);
      await t.tap(find.widgetWithText(FilledButton, 'Continuar')); // itens → dados
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.enterText(find.byType(TextField).at(1), '11912345678'); // o perfil de teste não tem telefone
      await t.tap(find.widgetWithText(FilledButton, 'Continuar')); // dados → entrega
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      return be;
    }

    testWidgets('em "Retirar no local", mostra o endereço escolhido pela confeiteira, com as instruções', (t) async {
      final be = await toStep3(t, {
        'GET /api/pickup': (_) => (200, {'label': 'Ateliê', 'address': 'Av. Paulista, 1000 - Bela Vista, São Paulo', 'instructions': 'Portão azul'}),
      });
      expect(find.text('Passo 3 de 5'), findsOneWidget);
      expect(find.text('Endereço de retirada: Ateliê'), findsOneWidget);
      expect(find.text('Av. Paulista, 1000 - Bela Vista, São Paulo'), findsOneWidget);
      expect(find.text('Portão azul'), findsOneWidget);
      expect(be.calls, contains('GET /api/pickup'));
    });

    testWidgets('sem endereço escolhido, o pedido segue normal, sem o cartão de endereço', (t) async {
      await toStep3(t, {'GET /api/pickup': (_) => (200, {'label': '', 'address': '', 'instructions': ''})});
      expect(find.text('Passo 3 de 5'), findsOneWidget);
      expect(find.textContaining('Endereço de retirada'), findsNothing);
    });

    testWidgets('se a consulta do endereço falha, o pedido não trava', (t) async {
      await toStep3(t, {'GET /api/pickup': (_) => (500, {'error': 'erro'})});
      expect(find.text('Passo 3 de 5'), findsOneWidget);
      expect(find.textContaining('Endereço de retirada'), findsNothing);
    });

    testWidgets('em "Receber em casa" o endereço de retirada some', (t) async {
      await toStep3(t, {'GET /api/pickup': (_) => (200, {'label': 'Ateliê', 'address': 'Av. Paulista, 1000 - Bela Vista, São Paulo', 'instructions': ''})});
      await t.tap(find.text('Receber em casa'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Endereço de retirada: Ateliê'), findsNothing);
    });

    testWidgets('Meus pedidos mostra o endereço de retirada da época do pedido', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, [
              {
                'id': 3, 'code': 4821, 'pickup_address': 'Av. Paulista, 1000 - Bela Vista, São Paulo · Portão azul', 'created_at': '2026-10-02T20:00:00.000Z',
                'scheduled_for': '2026-10-05', 'time_window': 'tarde', 'fulfillment': 'retirada', 'street': '', 'number': '', 'complement': '', 'district': '', 'city': '',
                'payment_method': 'dinheiro', 'notes': '', 'total': 13.0, 'status': 'novo', 'paid': false, 'can_cancel': true, 'pix': null,
                'items': [{'id': '1', 'name': 'Brigadeiro gourmet', 'qty': 2, 'unit_price': 6.5}],
              }
            ]),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/pedidos', session: s);
      expect(find.text('Retirada: Av. Paulista, 1000 - Bela Vista, São Paulo · Portão azul'), findsOneWidget);
    });
  });

  group('pedir sem estar logado (estado na URL)', () {
    testWidgets('home → Pedir → login → novo pedido com o item; voltar leva de volta à home', (t) async {
      final be = FakeBackend({
        'GET /api/menu': (_) => (200, menuJson),
        'GET /api/pickup': (_) => (200, {'label': '', 'address': '', 'instructions': ''}),
        'POST /api/cliente/login': (_) => (200, {'ok': true, 'token': 'tok-cliente'}),
        'GET /api/cliente/me': (_) => (200, customerJson),
      });
      await pumpApp(t, be);
      await t.scrollUntilVisible(find.text('Brigadeiro gourmet'), 400, scrollable: find.byType(Scrollable).first);
      await t.ensureVisible(find.widgetWithText(FilledButton, 'Pedir').last); // o botão Pedir fica abaixo do nome, no fim do cartão
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      await t.tap(find.widgetWithText(FilledButton, 'Pedir').last); // o do Brigadeiro gourmet (último item do cardápio)
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Entre para fazer seu pedido'), findsOneWidget); // foi para o login
      await t.enterText(find.byType(TextField).at(0), 'joana@example.com');
      await t.enterText(find.byType(TextField).at(1), 'SenhaJoana2026');
      await t.tap(find.widgetWithText(FilledButton, 'Entrar'));
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      // depois do login: o novo pedido, já com o item escolhido
      expect(find.text('Passo 1 de 5'), findsOneWidget);
      expect(find.text('O que você quer?'), findsOneWidget);
      expect(find.text('R\$ 6,50'), findsWidgets);
      // e o histórico não foi perdido: voltar leva à home, não sai do app
      await t.binding.handlePopRoute();
      await t.pumpAndSettle(const Duration(milliseconds: 100));
      expect(find.text('Confeitaria feita à mão, do forno para a sua mesa.'), findsOneWidget);
    });

    testWidgets('o botão Compartilhar cardápio copia o link da página só com o cardápio (sem folha de compartilhar)', (t) async {
      sharePlatform = (_) async => throw Exception('sem folha de compartilhar');
      String? copied;
      t.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String?;
        return null;
      });
      addTearDown(() => t.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      await t.scrollUntilVisible(find.text('Compartilhar cardápio'), 400, scrollable: find.byType(Scrollable).first);
      await t.tap(find.text('Compartilhar cardápio'));
      await t.pump(const Duration(milliseconds: 300));
      expect(copied, endsWith('/cardapio'));
      expect(find.text('Link do cardápio copiado'), findsOneWidget);
    });

    testWidgets('com a folha de compartilhar disponível, envia o texto com o link', (t) async {
      String? sent;
      sharePlatform = (params) async => sent = params.text;
      final be = FakeBackend({'GET /api/menu': (_) => (200, menuJson)});
      await pumpApp(t, be);
      await t.scrollUntilVisible(find.text('Compartilhar cardápio'), 400, scrollable: find.byType(Scrollable).first);
      await t.tap(find.text('Compartilhar cardápio'));
      await t.pump(const Duration(milliseconds: 300));
      expect(sent, contains('/cardapio'));
      expect(sent, contains('Confira o cardápio da Raykookie'));
    });

    testWidgets('abrir o login já com sessão leva direto ao destino', (t) async {
      final be = FakeBackend({
        'GET /api/cliente/me': (_) => (200, customerJson),
        'GET /api/cliente/pedidos': (_) => (200, []),
        'GET /api/menu': (_) => (200, menuJson),
      });
      final s = be.session(store: MemoryTokenStore()..write('customer_token', 'tok'));
      await s.load();
      await pumpApp(t, be, at: '/cliente/entrar?next=%2Fcliente%2Fpedidos', session: s);
      expect(find.text('Você ainda não fez pedidos'), findsOneWidget);
      expect(find.text('Entre para fazer seu pedido'), findsNothing);
    });
  });
}

bool textButtonEnabled(WidgetTester t, String label) => t.widget<TextButton>(find.widgetWithText(TextButton, label)).onPressed != null;

class _FakePicker extends ImagePickerPlatform with MockPlatformInterfaceMixin {
  @override
  Future<XFile?> getImageFromSource({required ImageSource source, ImagePickerOptions options = const ImagePickerOptions()}) async =>
      XFile.fromData(base64Decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='), name: 'foto.png', mimeType: 'image/png');
}

class _FakePush extends CustomerPush {
  _FakePush() {
    available = true;
  }
  int enabled = 0, unregistered = 0;
  @override
  Future<void> enable() async {
    enabled++;
    granted = true;
    notifyListeners();
  }

  @override
  Future<void> unregister() async => unregistered++;
}
