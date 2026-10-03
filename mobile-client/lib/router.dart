import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:raykookie_common/core/session.dart';

import 'features/customer/customer_auth_screen.dart';
import 'features/customer/home_screen.dart';
import 'features/customer/new_order_screen.dart';
import 'features/customer/orders_screen.dart';
import 'package:raykookie_common/ui/legal.dart';
import 'features/customer/my_data_screen.dart';
import 'features/customer/refund_request_screen.dart';
import 'features/customer/brand.dart';

final _rootKey = GlobalKey<NavigatorState>(debugLabel: 'root');

/// Só aceita caminhos internos da área do cliente (igual ao safeNext do web).
String safeNext(String? v) => (v != null && v.startsWith('/cliente/') && !v.startsWith('//')) ? v : '/cliente/pedidos';

/// Rotas do app do cliente:
///   /                       página inicial da loja (cardápio)
///   /cliente/entrar         login (Google ou e-mail e senha)
///   /cliente/pedidos        pedidos já feitos
///   /cliente/pedidos/novo   novo pedido
///   /cliente/conta         Meus dados (LGPD)
///   /termos, /privacidade   Termos de Serviço e Política de Privacidade
GoRouter buildRouter(Session session, {String initialLocation = '/'}) => GoRouter(
      navigatorKey: _rootKey,
      initialLocation: initialLocation,
      refreshListenable: session,
      redirect: (context, state) {
        final loc = state.matchedLocation;
        if (loc == '/cliente') return '/cliente/pedidos';
        if ((loc.startsWith('/cliente/pedidos') || loc == '/cliente/conta') && !session.isCustomer) {
          return '/cliente/entrar?next=${Uri.encodeComponent(state.uri.toString())}';
        }
        return null;
      },
      routes: [
        GoRoute(path: '/', builder: (_, _) => const HomeScreen()),
        GoRoute(path: '/termos', builder: (_, _) => BrandScope(child: LegalScreen(docId: 'termos', api: session.customerApi))),
        GoRoute(path: '/privacidade', builder: (_, _) => BrandScope(child: LegalScreen(docId: 'privacidade', api: session.customerApi))),
        GoRoute(path: '/cliente/entrar', builder: (_, s) => CustomerAuthScreen(next: safeNext(s.uri.queryParameters['next']))),
        GoRoute(path: '/cliente/conta', builder: (_, _) => const MyDataScreen()),
        GoRoute(path: '/cliente/pedidos', builder: (_, s) => CustomerOrdersScreen(justCreated: s.uri.queryParameters['novo'], focus: s.uri.queryParameters['pedido'])),
        GoRoute(path: '/cliente/pedidos/:id/devolucao', builder: (_, s) => RefundRequestScreen(orderId: int.tryParse(s.pathParameters['id'] ?? '') ?? 0)),
        GoRoute(path: '/cliente/pedidos/novo', builder: (_, s) => NewOrderScreen(initialItem: s.uri.queryParameters['item'])),
      ],
    );
