import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

/// Editor de cardápio: escolhe quais receitas aparecem na loja e quais características (tags fixas)
/// cada uma mostra. Cada mudança salva na hora (PATCH /api/recipes/:id/cardapio) — "Ver cardápio" abre
/// o site, que já reflete o que acabou de ser salvo (não existe rascunho/publicação separados).
class CardapioScreen extends StatefulWidget {
  const CardapioScreen({super.key});
  @override
  State<CardapioScreen> createState() => _CardapioScreenState();
}

class _CardapioScreenState extends State<CardapioScreen> {
  int _v = 0;

  @override
  Widget build(BuildContext context) {
    final api = context.read<Session>().ownerApi;
    final c = context.c;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Editor de cardápio'),
        actions: [
          TextButton.icon(
            onPressed: () => launchUrl(Uri.parse('${AppConfig.siteBase}/cardapio'), mode: LaunchMode.externalApplication),
            icon: const Icon(Icons.visibility_outlined),
            label: const Text('Ver cardápio'),
          ),
        ],
      ),
      body: Loader<List<Map<String, dynamic>>>(
        key: ValueKey(_v),
        load: () async => [for (final e in await api.get('/api/recipes/cardapio') as List) Map<String, dynamic>.from(e as Map)],
        builder: (context, list, reload) {
          if (list.isEmpty) {
            return Padding(
              padding: const EdgeInsets.only(top: 24),
              child: Center(child: Text('Cadastre uma receita primeiro, em Receitas.', style: TextStyle(color: c.muted))),
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Escolha o que aparece no cardápio da loja e, para cada item, quais características mostrar (alergênicos, dieta...).',
                style: TextStyle(color: c.muted),
              ),
              const SizedBox(height: 14),
              for (final r in list)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: _RecipeMenuCard(r, onSaved: () => setState(() => _v++)),
                ),
            ],
          );
        },
      ),
    );
  }
}

class _RecipeMenuCard extends StatefulWidget {
  const _RecipeMenuCard(this.r, {required this.onSaved});
  final Map<String, dynamic> r;
  final VoidCallback onSaved;
  @override
  State<_RecipeMenuCard> createState() => _RecipeMenuCardState();
}

class _RecipeMenuCardState extends State<_RecipeMenuCard> {
  late bool _show = widget.r['show_on_menu'] == true;
  late Set<String> _tags = {for (final t in widget.r['tags'] as List) '$t'};
  bool _busy = false;

  Future<void> _patch(Map<String, dynamic> body) async {
    setState(() => _busy = true);
    try {
      await context.read<Session>().ownerApi.patch('/api/recipes/${widget.r['id']}/cardapio', body);
      widget.onSaved();
    } on ApiException catch (e) {
      Toast.error(e.message);
      setState(() {
        _show = widget.r['show_on_menu'] == true;
        _tags = {for (final t in widget.r['tags'] as List) '$t'};
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final priced = widget.r['priced'] == true;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text('${widget.r['name']}', style: const TextStyle(fontWeight: FontWeight.w800))),
              if (_busy) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              Switch(
                value: _show,
                activeTrackColor: c.pink,
                onChanged: _busy
                    ? null
                    : (v) {
                        setState(() => _show = v);
                        _patch({'show_on_menu': v});
                      },
              ),
            ],
          ),
          if (!priced)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text('Sem preço ainda: não aparece no cardápio mesmo ligado. Adicione os ingredientes em Receitas.', style: TextStyle(color: c.warn, fontSize: 12)),
            ),
          if (_show) ...[
            const SizedBox(height: 10),
            Text('Características', style: TextStyle(color: c.muted, fontSize: 12, fontWeight: FontWeight.w700)),
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final id in menuTagIds)
                  FilterChip(
                    label: Text(menuTagLabel(id)),
                    selected: _tags.contains(id),
                    onSelected: _busy
                        ? null
                        : (sel) {
                            setState(() => sel ? _tags.add(id) : _tags.remove(id));
                            _patch({'tags': _tags.toList()});
                          },
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
