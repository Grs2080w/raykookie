import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/ui/category_chip.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

class RecipesScreen extends StatelessWidget {
  const RecipesScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    return Scaffold(
      backgroundColor: Colors.transparent,
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/painel/receitas/nova'),
        icon: const Icon(Icons.add_rounded),
        label: const Text(
          'Nova receita',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
      ),
      body: Loader<(List<RecipeRow>, List<Category>)>(
        load: () async {
          final r = await Future.wait([
            api.get('/api/recipes'),
            api.get('/api/categories?kind=recipe'),
          ]);
          return (
            [for (final e in r[0] as List) RecipeRow.fromJson(e)],
            [for (final e in r[1] as List) Category.fromJson(e)],
          );
        },
        builder: (context, data, reload) {
          final c = context.c;
          final (recipes, cats) = data;
          final catMap = {for (final x in cats) x.id: x};
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 12),
              const PageTitle(
                'Receitas e preços',
                subtitle: 'Cada receita mostra o custo por unidade e o preço para vender com a margem que você quer.',
              ),
              const InfoBanner(
                'Todas as receitas com preço aparecem na Landing page, mas apenas foto, nome e preço por unidade vão para lá. Ingredientes, custos e margem ficam só aqui no painel.',
              ),
              if (recipes.isNotEmpty) ...[
                const SizedBox(height: 10),
                const ShareMenuButton(expand: true),
                const SizedBox(height: 8),
                const _StoryButton(),
              ],
              const SizedBox(height: 14),
              if (recipes.isEmpty)
                EmptyState(
                  icon: Icons.restaurant_menu_outlined,
                  title: 'Nenhuma receita ainda',
                  text: 'Monte a primeira escolhendo ingredientes do estoque. O preço aparece enquanto você preenche.',
                  action: BusyButton(
                    label: 'Criar receita',
                    icon: Icons.add_rounded,
                    expand: false,
                    onPressed: () => context.push('/painel/receitas/nova'),
                  ),
                )
              else
                for (final r in recipes)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: AppCard(
                      onTap: () async {
                        await context.push('/painel/receitas/${r.id}');
                        await reload();
                      },
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(
                                child: Text(
                                  r.name,
                                  style: display(
                                    context,
                                    size: 21,
                                    height: 1.2,
                                  ),
                                ),
                              ),
                              if (catMap[r.categoryId] != null)
                                Flexible(
                                  child: CategoryChip(
                                    name: catMap[r.categoryId]!.name,
                                    icon: catMap[r.categoryId]!.icon,
                                    color: catMap[r.categoryId]!.color,
                                  ),
                                ),
                            ],
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Rende ${number(r.yieldUnits)} un${r.unitWeightG > 0 ? ' de ${number(r.unitWeightG)} g' : ''} · ${r.items.length} ingrediente${r.items.length == 1 ? '' : 's'}',
                            style: TextStyle(color: c.muted, fontSize: 13.5),
                          ),
                          const SizedBox(height: 6),
                          (r.pricing?.rounded ?? 0) > 0
                              ? Text(
                                  'Na Landing page${r.photoV != null ? ' · com foto' : ''}',
                                  style: TextStyle(
                                    color: c.pinkStrong,
                                    fontWeight: FontWeight.w700,
                                    fontSize: 12,
                                  ),
                                )
                              : const Align(
                                  alignment: Alignment.centerLeft,
                                  child: AppBadge(
                                    'Fora da Landing page (sem preço)',
                                    tone: 'warn',
                                    dot: false,
                                  ),
                                ),
                          const SizedBox(height: 10),
                          Divider(color: c.line),
                          const SizedBox(height: 6),
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      'Preço sugerido',
                                      style: TextStyle(
                                        color: c.muted,
                                        fontSize: 12,
                                      ),
                                    ),
                                    Text(
                                      money(r.pricing?.rounded ?? 0),
                                      style: display(
                                        context,
                                        size: 30,
                                        color: c.pinkStrong,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              Column(
                                crossAxisAlignment: CrossAxisAlignment.end,
                                children: [
                                  Text.rich(
                                    TextSpan(
                                      style: TextStyle(
                                        color: c.muted,
                                        fontSize: 13,
                                      ),
                                      children: [
                                        const TextSpan(text: 'Custo '),
                                        TextSpan(
                                          text: money(r.pricing?.perUnit ?? 0),
                                          style: TextStyle(
                                            color: c.ink,
                                            fontWeight: FontWeight.w700,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                  Text(
                                    'Lucro ${money(r.pricing?.profitPerUnit ?? 0)} · ${percent(r.pricing?.realMarginPct ?? 0)}',
                                    style: TextStyle(
                                      color: c.ok,
                                      fontWeight: FontWeight.w700,
                                      fontSize: 13,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
            ],
          );
        },
      ),
    );
  }
}

/// "Story do cardápio": gera a imagem 1080×1920 no servidor e abre a folha de compartilhar (Instagram, WhatsApp...).
class _StoryButton extends StatefulWidget {
  const _StoryButton();
  @override
  State<_StoryButton> createState() => _StoryButtonState();
}

class _StoryButtonState extends State<_StoryButton> {
  bool _busy = false;

  Future<void> _share() async {
    setState(() => _busy = true);
    try {
      final bytes = await context.read<Session>().ownerApi.getBytes('/api/story/cardapio');
      await shareImage(bytes, name: 'story-cardapio.png');
    } on ApiException catch (e) {
      Toast.error(e.message);
    } catch (_) {
      Toast.error('Não foi possível compartilhar a imagem.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => BusyButton(label: 'Story do cardápio', icon: Icons.photo_outlined, outlined: true, busy: _busy, onPressed: _share);
}
