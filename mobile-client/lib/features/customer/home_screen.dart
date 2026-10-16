import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:raykookie_common/core/analytics.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/seen_once.dart';
import 'package:raykookie_common/ui/legal.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'brand.dart';
import 'feedback_card.dart';
import 'menu_view.dart';

// Cores das seções escuras (chocolate): texto e rosa legíveis sobre o fundo; iguais às do site.
const _onDark = Color(0xFFFBF3EA);
const _mutedOnDark = Color(0xFFD9BFAE);
const _pinkOnDark = Color(0xFFFF82B0);

/// Fotos de vitrine da casa (as mesmas do site).
String get _showcase => AppConfig.siteBase;

/// Página inicial do app (equivale à home do site): apresentação, cardápio, como pedir, história, galeria e depoimentos.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late Future<Menu> _menu;
  final _cardapio = GlobalKey();
  final _heroCta = GlobalKey();
  final _scroll = ScrollController();
  String? _group; // categoria escolhida no cardápio (nulo = todas)
  bool _showBar = false; // barra fixa só aparece quando o botão principal do topo sai da tela

  @override
  void initState() {
    super.initState();
    _menu = _loadMenu();
    _scroll.addListener(_syncBar);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncBar());
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _syncBar() {
    final box = _heroCta.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.attached) return;
    final bottom = box.localToGlobal(Offset.zero).dy + box.size.height;
    final gone = bottom < MediaQuery.paddingOf(context).top + kToolbarHeight;
    if (gone != _showBar) setState(() => _showBar = gone);
  }

  Future<Menu> _loadMenu() async {
    final r = await context.read<Session>().customerApi.get('/api/menu');
    return Menu.fromJson(r as Map<String, dynamic>);
  }

  void _order() => context.push('/cliente/pedidos');
  void _scrollToMenu() => Scrollable.ensureVisible(
    _cardapio.currentContext!,
    duration: const Duration(milliseconds: 450),
    curve: Curves.easeOutCubic,
  );

  @override
  Widget build(BuildContext context) {
    return BrandScope(
      child: Builder(
        builder: (context) {
          final c = context.c;
          return Scaffold(
            appBar: BrandAppBar(
              actions: [
                TextButton(
                  onPressed: () => context.push('/cliente/pedidos'),
                  style: TextButton.styleFrom(foregroundColor: c.ink, minimumSize: const Size(0, 44)),
                  child: const Text('Meus pedidos'),
                ),
              ],
            ),
            body: SingleChildScrollView(
              controller: _scroll,
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                    child: Centered(child: UpdateBanner(api: context.read<Session>().customerApi, app: 'client')),
                  ),
                  _hero(context),
                  _band(context),
                  _menuSection(context),
                  _howToOrder(context),
                  _about(context),
                  _gallery(context),
                  _reviews(context),
                  _feedback(context),
                  _instagram(context),
                  _footer(context),
                ],
              ),
            ),
            // a barra ocupa espaço de verdade (não flutua sobre o conteúdo): o fim da página nunca fica escondido
            bottomNavigationBar: AnimatedSize(
              duration: const Duration(milliseconds: 280),
              curve: Curves.easeOutCubic,
              alignment: Alignment.topCenter,
              child: _showBar
                  ? Container(
                      width: double.infinity,
                      decoration: BoxDecoration(color: c.bg, border: Border(top: BorderSide(color: c.line))),
                      child: SafeArea(
                        top: false,
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
                          child: BusyButton(label: 'Fazer pedido', onPressed: _order),
                        ),
                      ),
                    )
                  : const SizedBox(width: double.infinity),
            ),
          );
        },
      ),
    );
  }

  // ---- seções -----------------------------------------------------------------------------------

  /// Apresentação: título, pedir, e a foto em arco (a boca do forno) com o cookie do logo atrás.
  Widget _hero(BuildContext context) {
    final c = context.c;
    return Container(
      color: c.bg,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 44),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Confeitaria feita à mão, do forno para a sua mesa.', style: display(context, size: 42, height: 1.05)),
            const SizedBox(height: 14),
            Text(
              'Cookies, tortas, brownies e bolos preparados em pequenas fornadas, com ingredientes selecionados e muito cuidado em cada detalhe.',
              style: TextStyle(color: c.muted, fontSize: 17, height: 1.45),
            ),
            const SizedBox(height: 22),
            KeyedSubtree(
              key: _heroCta,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 12,
                runSpacing: 4,
                children: [
                  BusyButton(label: 'Fazer pedido', expand: false, onPressed: _order),
                  TextButton(onPressed: _scrollToMenu, style: TextButton.styleFrom(minimumSize: const Size(0, 48)), child: const Text('Ver cardápio')),
                ],
              ),
            ),
            const SizedBox(height: 6),
            Text('Sob encomenda: peça com ${AppConfig.leadDaysLabel}.', style: TextStyle(color: c.muted, fontSize: 13.5)),
            const SizedBox(height: 30),
            LayoutBuilder(
              builder: (context, box) {
                final w = box.maxWidth;
                final archW = w * 0.74;
                return SizedBox(
                  height: w * 1.1,
                  child: Stack(
                    clipBehavior: Clip.none,
                    children: [
                      // o símbolo do logo, girado para a mordida ficar à vista abaixo do arco
                      Positioned(
                        right: -w * 0.06,
                        bottom: -w * 0.04,
                        child: Transform.rotate(angle: 125 * math.pi / 180, child: SvgPicture.asset('assets/brand/raykookie-simbolo-cookie.svg', width: w * 0.5)),
                      ),
                      Positioned(right: 0, top: 0, child: _Arch(url: '$_showcase/fotos/cookie-destaque.jpg', width: archW)),
                      Positioned(
                        left: 0,
                        bottom: 0,
                        width: w * 0.4,
                        child: Container(
                          decoration: BoxDecoration(color: c.bg, borderRadius: BorderRadius.circular(28)),
                          padding: const EdgeInsets.all(5),
                          child: AspectRatio(
                            aspectRatio: 4 / 5,
                            child: ClipRRect(borderRadius: BorderRadius.circular(23), child: _NetPhoto(url: '$_showcase/fotos/brownie-destaque.jpg')),
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  /// Faixa dos três diferenciais: só texto, sobre chocolate.
  Widget _band(BuildContext context) {
    final c = context.c;
    final items = [
      ('Feito à mão', 'Cada receita é preparada do zero, sem pressa.'),
      ('Ingredientes selecionados', 'Chocolate de verdade, manteiga e frutas frescas.'),
      ('Sob encomenda', 'Peça com pelo menos ${AppConfig.leadDaysLabel}.'),
    ];
    return Container(
      color: c.choco,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 30, 20, 30),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (title, text) in items)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 9),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: display(context, size: 21, color: _onDark)),
                    const SizedBox(height: 3),
                    Text(text, style: const TextStyle(color: _mutedOnDark, height: 1.4)),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _menuSection(BuildContext context) {
    final c = context.c;
    return Padding(
      key: _cardapio,
      padding: const EdgeInsets.fromLTRB(20, 48, 20, 48),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Assado depois do seu pedido', style: display(context, size: 34, height: 1.08)),
            const SizedBox(height: 10),
            Text('Escolha seus favoritos e peça com ${AppConfig.leadDaysLabel}. Cada fornada é feita para você.', style: TextStyle(color: c.muted, fontSize: 16, height: 1.4)),
            const SizedBox(height: 14),
            const ShareMenuButton(),
            const SizedBox(height: 18),
            FutureBuilder<Menu>(
              future: _menu,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) {
                  return const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()));
                }
                if (snap.hasError) {
                  return Column(
                    children: [
                      Text('Não conseguimos carregar o cardápio agora.', style: TextStyle(color: c.muted)),
                      const SizedBox(height: 10),
                      BusyButton(label: 'Tentar de novo', expand: false, outlined: true, onPressed: () => setState(() => _menu = _loadMenu())),
                    ],
                  );
                }
                final groups = presentGroups(snap.data!);
                if (groups.isEmpty) {
                  return Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(28),
                    decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(24)),
                    child: Text('Nosso cardápio está sendo preparado. Volte em breve!', textAlign: TextAlign.center, style: TextStyle(color: c.muted)),
                  );
                }
                final shown = [for (final g in groups) if (_group == null || g.id == _group) g];
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (groups.length > 1)
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            _chip(context, 'Todos', _group == null, () => setState(() => _group = null)),
                            for (final g in groups) _chip(context, g.name, _group == g.id, () => setState(() => _group = g.id)),
                          ],
                        ),
                      ),
                    for (final g in shown) ...[
                      if (groups.length > 1 && _group == null)
                        Padding(padding: const EdgeInsets.only(top: 26, bottom: 12), child: Text(g.name, style: display(context, size: 26))),
                      if (groups.length == 1 || _group != null) const SizedBox(height: 16),
                      ..._cards(context, g),
                    ],
                    const SizedBox(height: 22),
                    Text('Precisa de outro sabor, tamanho ou decoração? Conte no campo de observações do pedido.', style: TextStyle(color: c.muted, fontSize: 13.5, height: 1.4)),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  /// Chips das categorias: pílulas; a escolhida fica em cacau.
  Widget _chip(BuildContext context, String label, bool selected, VoidCallback onTap) {
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) => onTap(),
        showCheckmark: false,
        backgroundColor: c.surface2,
        selectedColor: c.ink,
        side: BorderSide.none,
        labelStyle: TextStyle(color: selected ? c.bg : c.ink, fontWeight: FontWeight.w700, fontSize: 14),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
        materialTapTargetSize: MaterialTapTargetSize.padded,
      ),
    );
  }

  /// Os cartões de um grupo: combos ocupam a largura toda; itens avulsos vão de dois em dois, com a mesma altura.
  List<Widget> _cards(BuildContext context, MenuGroup g) {
    final combos = g.items.any((m) => m.combo != null);
    if (combos) {
      return [for (final m in g.items) Padding(padding: const EdgeInsets.only(bottom: 14), child: _menuCard(context, m, fill: false))];
    }
    return [
      for (var i = 0; i < g.items.length; i += 2)
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: _menuCard(context, g.items[i], fill: true)),
                const SizedBox(width: 12),
                Expanded(child: i + 1 < g.items.length ? _menuCard(context, g.items[i + 1], fill: true) : const SizedBox.shrink()),
              ],
            ),
          ),
        ),
    ];
  }

  void _open(MenuItem m) {
    Analytics.instance.track('menu_item_click', itemId: m.id, detail: 'home_app');
    context.push('/cliente/pedidos/novo?item=${m.id}');
  }

  /// Cartão de um item do cardápio. Na medição anônima conta "visto" quando metade dele aparece na tela.
  Widget _menuCard(BuildContext context, MenuItem m, {required bool fill}) => SeenOnce(
        key: ValueKey('seen-${m.id}'),
        onSeen: () => Analytics.instance.track('menu_item_view', itemId: m.id, detail: 'home_app'),
        child: _menuCardBody(context, m, fill: fill),
      );

  Widget _menuCardBody(BuildContext context, MenuItem m, {required bool fill}) {
    final c = context.c;
    final api = context.read<Session>().customerApi;
    final name = niceName(m.name);
    final combo = m.combo;
    final save = combo != null && combo.savings > 0 ? combo : null;
    // "Vegetariano", "Sem lactose"… um chip cada; os "Contém …" se juntam num só ("Contém soja e ovos")
    final diet = [for (final t in m.tags) if (!t.startsWith('contem_')) menuTagLabel(t)];
    final contains = [for (final t in m.tags) if (t.startsWith('contem_')) menuTagLabel(t).replaceFirst('Contém ', '')];
    final containsLabel = contains.isEmpty ? '' : 'Contém ${contains.length > 1 ? '${contains.sublist(0, contains.length - 1).join(', ')} e ${contains.last}' : contains.first}';
    Widget chip(String t) => Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1.5),
          decoration: BoxDecoration(color: c.surface2, borderRadius: BorderRadius.circular(14)),
          child: Text(t, style: TextStyle(color: c.muted, fontWeight: FontWeight.w600, fontSize: 11.5, height: 1.4)),
        );
    final info = <Widget>[
      Text(name, style: display(context, size: 17.5, height: 1.2)),
      if (weightLabel(m.weightG).isNotEmpty) Padding(padding: const EdgeInsets.only(top: 3), child: Text('${weightLabel(m.weightG)} cada', style: TextStyle(color: c.muted, fontSize: 13))),
      if (combo != null && combo.parts.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final p in combo.parts)
                Padding(
                  padding: const EdgeInsets.only(bottom: 3),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Padding(padding: const EdgeInsets.only(top: 7, right: 8), child: Container(width: 6, height: 6, decoration: BoxDecoration(color: c.rosa, shape: BoxShape.circle))),
                    Expanded(child: Text('${p.qty} ${p.name}', style: TextStyle(color: c.muted, fontSize: 13.5, height: 1.35))),
                  ]),
                ),
            ],
          ),
        )
      else if (combo != null)
        Padding(padding: const EdgeInsets.only(top: 6), child: Text(combo.contents, style: TextStyle(color: c.muted, fontSize: 13.5, height: 1.35))),
      if (m.ingredients.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('Ingredientes: ${m.ingredients.join(', ')}', style: TextStyle(color: c.muted, fontSize: 13))),
      if (save != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
            decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(99)),
            child: Text('Economiza ${money(save.savings)}', style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700, fontSize: 13)),
          ),
        ),
      if (diet.isNotEmpty || containsLabel.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Wrap(spacing: 4, runSpacing: 4, children: [for (final t in diet) chip(t), if (containsLabel.isNotEmpty) chip(containsLabel)]),
        ),
    ];
    final foot = <Widget>[
      const SizedBox(height: 10),
      if (save != null) Text('avulso ${money(save.fullPrice)}', style: TextStyle(color: c.muted, fontSize: 13, decoration: TextDecoration.lineThrough)),
      Text(money(m.price), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 20, letterSpacing: -0.2)),
      const SizedBox(height: 10),
      Semantics(
        button: true,
        label: 'Pedir $name',
        excludeSemantics: true,
        child: SizedBox(
          width: double.infinity,
          child: FilledButton(onPressed: () => _open(m), style: FilledButton.styleFrom(minimumSize: const Size(0, 44)), child: const Text('Pedir')),
        ),
      ),
    ];
    return Material(
      color: c.surface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24), side: BorderSide(color: c.line)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _open(m),
        excludeFromSemantics: true, // o cartão inteiro é só uma área de toque a mais; para leitores de tela a ação é o botão "Pedir"
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: fill ? MainAxisSize.max : MainAxisSize.min,
          children: [
            PhotoTile(kind: photoKind(m.name, m.category), url: m.image == null ? null : api.absolute(m.image!), radius: 0, aspectRatio: 4 / 3),
            if (fill)
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [...info, const Spacer(), ...foot]),
                ),
              )
            else
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [...info, ...foot]),
              ),
          ],
        ),
      ),
    );
  }

  /// Como pedir: três passos numa sequência de verdade (os números têm função aqui).
  Widget _howToOrder(BuildContext context) {
    const steps = [
      ('1', 'Entre ou crie sua conta', 'Com o Google ou com e-mail e senha.'),
      ('2', 'Escolha do cardápio', 'Monte o pedido e diga quando quer receber.'),
      ('3', 'Receba ou retire', 'Acompanhe tudo pelo app. Só chamamos no WhatsApp se for necessário.'),
    ];
    final c = context.c;
    return Container(
      color: c.choco,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 52, 20, 52),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Faça sua encomenda', style: display(context, size: 34, color: _onDark, height: 1.08)),
            const SizedBox(height: 10),
            const Text('Em poucos minutos o pedido está feito, e você acompanha todo o andamento pelo app, na sua conta.', style: TextStyle(color: _mutedOnDark, fontSize: 16, height: 1.4)),
            const SizedBox(height: 20),
            BusyButton(label: 'Fazer pedido', expand: false, onPressed: _order),
            const SizedBox(height: 30),
            for (final (n, t, d) in steps)
              Padding(
                padding: const EdgeInsets.only(bottom: 22),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(width: 48, child: Text(n, style: display(context, size: 34, color: _pinkOnDark, height: 1))),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(t, style: display(context, size: 20, color: _onDark)),
                          const SizedBox(height: 3),
                          Text(d, style: const TextStyle(color: _mutedOnDark, height: 1.4)),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _about(BuildContext context) {
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 52, 20, 52),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            LayoutBuilder(builder: (context, box) => _Arch(url: '$_showcase/fotos/bancada-destaque.jpg', width: math.min(box.maxWidth, 300))),
            const SizedBox(height: 26),
            Text('Doces simples, feitos com capricho.', style: display(context, size: 32, height: 1.08)),
            const SizedBox(height: 14),
            Text(
              'A Raykookie nasceu na cozinha de casa, com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Hoje continuamos assim: fornadas pequenas, receitas que testamos até ficarem perfeitas e atenção a cada pedido.',
              style: TextStyle(color: c.muted, fontSize: 16, height: 1.5),
            ),
            const SizedBox(height: 10),
            Text(
              'Trabalhamos só sob encomenda, para que tudo chegue fresquinho. Escolha o que você quer, diga o dia e o resto é com a gente.',
              style: TextStyle(color: c.muted, fontSize: 16, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }

  /// Galeria: a foto de vitrine e as fotos reais do cardápio (distintas, com o nome do item); sem fotos, a seção some.
  Widget _gallery(BuildContext context) {
    final c = context.c;
    return FutureBuilder<Menu>(
      future: _menu,
      builder: (context, snap) {
        final api = context.read<Session>().customerApi;
        final seen = <String, (String, String, bool)>{};
        for (final i in snap.data?.items ?? const <MenuItem>[]) {
          if (i.image == null) continue;
          final key = i.image!.split('?').first;
          final cur = seen[key];
          if (cur == null || (cur.$3 && i.combo == null)) seen[key] = (api.absolute(i.image!), niceName(i.name), i.combo != null);
        }
        final shots = <(String, String)>[('$_showcase/fotos/bolo-destaque.jpg', ''), for (final s in seen.values) (s.$1, s.$2)];
        return Container(
          color: c.surface2,
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 52),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(padding: const EdgeInsets.symmetric(horizontal: 20), child: Text('Um gostinho do que fazemos', style: display(context, size: 32, height: 1.08))),
              const SizedBox(height: 20),
              SizedBox(
                height: 250,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  itemCount: shots.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 12),
                  itemBuilder: (_, i) => SizedBox(
                    width: 220,
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(24),
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          _NetPhoto(url: shots[i].$1),
                          if (shots[i].$2.isNotEmpty)
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              child: Container(
                                padding: const EdgeInsets.fromLTRB(12, 28, 12, 12),
                                decoration: const BoxDecoration(gradient: LinearGradient(begin: Alignment.bottomCenter, end: Alignment.topCenter, colors: [Color(0xB82A160F), Color(0x002A160F)])),
                                child: Text(shots[i].$2, style: const TextStyle(color: Color(0xFFFFFAF4), fontWeight: FontWeight.w600, fontSize: 13.5)),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// Depoimentos: um em destaque e os outros ao lado (não três cartões iguais). Texto fixo, como no site.
  Widget _reviews(BuildContext context) {
    final c = context.c;
    const reviews = [
      ('Mariana S.', 'Aniversário de 30 anos', 'O bolo chegou lindo e estava ainda mais gostoso do que parecia. Todo mundo na festa pediu o contato!'),
      ('Carlos e Ana', 'Presente de casamento', 'Pedimos a caixa de brownies de presente e foi sucesso. Embalagem caprichada, entrega pontual.'),
      ('Patrícia L.', 'Cliente frequente', 'Os cookies de Ninho com Nutella viciam. Já virou tradição de sexta-feira aqui em casa.'),
    ];
    Widget stars() => Row(children: [for (var i = 0; i < 5; i++) Icon(Icons.star_rounded, color: c.pink, size: 20)]);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 52, 20, 52),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Quem já provou, recomenda', style: display(context, size: 32, height: 1.08)),
            const SizedBox(height: 20),
            for (var i = 0; i < reviews.length; i++)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 12),
                padding: EdgeInsets.all(i == 0 ? 24 : 18),
                decoration: BoxDecoration(color: i == 0 ? c.pinkTint : c.surface, borderRadius: BorderRadius.circular(24), border: i == 0 ? null : Border.all(color: c.line)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    stars(),
                    SizedBox(height: i == 0 ? 16 : 10),
                    Text('“${reviews[i].$3}”', style: i == 0 ? display(context, size: 24, height: 1.25) : TextStyle(fontSize: 15.5, height: 1.45, color: c.ink)),
                    SizedBox(height: i == 0 ? 16 : 12),
                    Text(reviews[i].$1, style: const TextStyle(fontWeight: FontWeight.w800)),
                    Text(reviews[i].$2, style: TextStyle(color: c.muted, fontSize: 13.5)),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _feedback(BuildContext context) {
    return Container(
      color: context.c.surface2,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 40, 20, 20),
      child: const Centered(maxWidth: 560, child: FeedbackCard()),
    );
  }

  /// "Siga no Instagram": bloco escuro com o símbolo da marca; o cartão inteiro abre o perfil.
  Widget _instagram(BuildContext context) {
    final c = context.c;
    return Container(
      color: c.surface2,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 44),
      child: Centered(
        maxWidth: 560,
        child: Material(
          color: c.choco,
          borderRadius: BorderRadius.circular(24),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => launchUrl(Uri.parse('https://www.instagram.com/raykookies'), mode: LaunchMode.externalApplication),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const RkLogo(variant: 'simbolo', height: 56),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(children: [
                              const Icon(Icons.camera_alt_outlined, size: 18, color: _onDark),
                              const SizedBox(width: 7),
                              Flexible(child: Text('@raykookies', style: display(context, size: 22, color: _onDark))),
                            ]),
                            const SizedBox(height: 4),
                            const Text('Fotos novas, bastidores e novidades da confeitaria. Vem seguir!', style: TextStyle(color: _mutedOnDark, fontSize: 14, height: 1.35)),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  Container(
                    constraints: const BoxConstraints(minHeight: 44),
                    padding: const EdgeInsets.symmetric(horizontal: 22),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(color: const Color(0xFFFFFAF4), borderRadius: BorderRadius.circular(99)),
                    child: const Text('Seguir', style: TextStyle(color: Color(0xFF2A160F), fontWeight: FontWeight.w700)),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _footer(BuildContext context) {
    final c = context.c;
    return Container(
      width: double.infinity,
      color: c.bg,
      padding: const EdgeInsets.fromLTRB(20, 36, 20, 36),
      child: Column(
        children: [
          const RkLogo(height: 30),
          const SizedBox(height: 12),
          Text('Confeitaria feita à mão. Encomendas com ${AppConfig.leadDaysLabel}.', textAlign: TextAlign.center, style: TextStyle(color: c.muted, fontSize: 13.5)),
          const SizedBox(height: 4),
          LegalLinks(onOpen: (id) => context.push('/$id')),
          const SizedBox(height: 6),
          const VersionLabel('Raykookie'),
        ],
      ),
    );
  }
}

/// Foto da rede com o fundo areia enquanto carrega ou se falhar (nunca um buraco).
class _NetPhoto extends StatelessWidget {
  const _NetPhoto({required this.url});
  final String url;
  @override
  Widget build(BuildContext context) {
    final fallback = ColoredBox(color: context.c.surface2);
    return Image.network(url, fit: BoxFit.cover, width: double.infinity, height: double.infinity, errorBuilder: (_, _, _) => fallback, loadingBuilder: (_, child, p) => p == null ? child : fallback);
  }
}

/// Foto em arco: o topo é um semicírculo (a boca do forno), 3:4.
class _Arch extends StatelessWidget {
  const _Arch({required this.url, required this.width});
  final String url;
  final double width;
  @override
  Widget build(BuildContext context) => ClipRRect(
        borderRadius: BorderRadius.only(topLeft: Radius.circular(width / 2), topRight: Radius.circular(width / 2), bottomLeft: const Radius.circular(16), bottomRight: const Radius.circular(16)),
        child: SizedBox(width: width, height: width * 4 / 3, child: _NetPhoto(url: url)),
      );
}
