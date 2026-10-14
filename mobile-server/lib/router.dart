import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:raykookie_common/core/session.dart';

import 'features/admin/admin_orders_screen.dart';
import 'features/admin/admin_shell.dart';
import 'features/admin/categories_screen.dart';
import 'features/admin/costs_screen.dart';
import 'features/admin/cardapio_screen.dart';
import 'features/admin/coupons_screen.dart';
import 'features/admin/dashboard_screen.dart';
import 'features/admin/engagement_screen.dart';
import 'features/admin/feedback_screen.dart';
import 'features/admin/goals_screen.dart';
import 'features/admin/more_screen.dart';
import 'features/admin/owner_login_screen.dart';
import 'features/admin/receipt_screen.dart';
import 'features/admin/recipe_editor_screen.dart';
import 'features/admin/recipes_screen.dart';
import 'features/admin/sound_screen.dart';
import 'features/admin/pickup_screen.dart';
import 'features/admin/privacy_screen.dart';
import 'features/admin/suppliers_screen.dart';
import 'features/admin/refunds_screen.dart';
import 'features/admin/stock_screen.dart';

final _rootKey = GlobalKey<NavigatorState>(debugLabel: 'root');

/// Rotas do app de gestão (tudo exige a conta da confeiteira):
///   /painel/entrar          login
///   /painel/...             início, estoque, receitas, pedidos e mais (metas, categorias, custos)
GoRouter buildRouter(
  Session session, {
  String initialLocation = '/painel/inicio',
}) => GoRouter(
  navigatorKey: _rootKey,
  initialLocation: initialLocation,
  refreshListenable: session,
  redirect: (context, state) {
    final loc = state.matchedLocation;
    if (loc == '/' || loc == '/painel') return '/painel/inicio';
    if (loc != '/painel/entrar' && !session.isOwner) return '/painel/entrar';
    if (loc == '/painel/entrar' && session.isOwner) return '/painel/inicio';
    return null;
  },
  routes: [
    GoRoute(
      path: '/painel/entrar',
      builder: (_, _) => const OwnerLoginScreen(),
    ),
    StatefulShellRoute.indexedStack(
      builder: (_, _, shell) => AdminShell(shell: shell),
      branches: [
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/painel/inicio',
              builder: (_, _) => const DashboardScreen(),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/painel/estoque',
              builder: (_, _) => const StockScreen(),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/painel/receitas',
              builder: (_, _) => const RecipesScreen(),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/painel/pedidos',
              builder: (_, _) => const AdminOrdersScreen(),
            ),
          ],
        ),
        StatefulShellBranch(
          routes: [
            GoRoute(
              path: '/painel/mais',
              builder: (_, _) => const MoreScreen(),
            ),
          ],
        ),
      ],
    ),
    // telas cheias por cima da navegação do painel
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/receitas/nova',
      builder: (_, _) => const RecipeEditorScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/receitas/:id',
      builder: (_, s) =>
          RecipeEditorScreen(id: int.tryParse(s.pathParameters['id'] ?? '')),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/estoque/nota',
      builder: (_, _) => const ReceiptScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/metas',
      builder: (_, _) => const GoalsScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/categorias',
      builder: (_, _) => const CategoriesScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/som',
      builder: (_, _) => const SoundScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/enderecos',
      builder: (_, _) => const PickupScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/fornecedores',
      builder: (_, _) => const SuppliersScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/devolucoes',
      builder: (_, _) => const RefundsScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/devolucoes/:id',
      builder: (_, s) => RefundDetailScreen(id: int.tryParse(s.pathParameters['id'] ?? '') ?? 0),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/cardapio',
      builder: (_, _) => const CardapioScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/cupons',
      builder: (_, _) => const CouponsScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/engajamento',
      builder: (_, _) => const EngagementScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/feedback',
      builder: (_, _) => const FeedbackScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/privacidade',
      builder: (_, _) => const PrivacyScreen(),
    ),
    GoRoute(
      parentNavigatorKey: _rootKey,
      path: '/painel/custos',
      builder: (_, _) => const CostsScreen(),
    ),
  ],
);
