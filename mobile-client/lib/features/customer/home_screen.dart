import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/config.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:raykookie_common/core/shop_rules.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/app_update.dart';
import 'package:raykookie_common/ui/legal.dart';
import 'package:raykookie_common/ui/share_menu.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'brand.dart';
import 'feedback_card.dart';

/// Página inicial do app (equivale à landing page do site): apresentação, cardápio, depoimentos e botão de pedido.
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

  /// Fotos reais do cardápio por categoria (nunca repete a mesma foto na tela); sem foto real, fica a ilustração.
  /// `used` é compartilhado entre as chamadas (hero e galeria) para garantir que nada se repita na página.
  Map<String, String> _assignPhotos(Menu? menu, ApiClient api, List<String> kinds, Set<String> used) {
    final out = <String, String>{};
    for (final kind in kinds) {
      final m = (menu?.items ?? const <MenuItem>[]).where((i) => i.image != null && !used.contains(i.image) && photoKind(i.name, i.category) == kind).firstOrNull;
      if (m?.image != null) {
        used.add(m!.image!);
        out[kind] = api.absolute(m.image!);
      }
    }
    return out;
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
                  style: TextButton.styleFrom(
                    foregroundColor: c.ink,
                    minimumSize: const Size(0, 44),
                  ),
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
                    child: Centered(
                      child: UpdateBanner(
                        api: context.read<Session>().customerApi,
                        app: 'client',
                      ),
                    ),
                  ),
                  _hero(context),
                  _highlights(context),
                  _about(context),
                  _gallery(context),
                  _menuSection(context),
                  _reviews(context),
                  _feedback(context),
                  _instagram(context),
                  _orderArea(context),
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
                  ? Material(
                      color: c.surface,
                      elevation: 6,
                      child: SafeArea(
                        top: false,
                        child: Container(
                          width: double.infinity,
                          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
                          decoration: BoxDecoration(
                            border: Border(top: BorderSide(color: c.line)),
                          ),
                          child: BusyButton(
                            label: 'Fazer pedido',
                            icon: Icons.shopping_bag_outlined,
                            onPressed: _order,
                          ),
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

  Widget _hero(BuildContext context) {
    final c = context.c;
    return Container(
      color: c.surface2,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
              decoration: BoxDecoration(
                color: c.surface,
                borderRadius: BorderRadius.circular(99),
                border: Border.all(color: c.line),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.favorite_rounded, size: 15, color: c.pinkStrong),
                  const SizedBox(width: 7),
                  Text(
                    'Sob encomenda, com carinho',
                    style: TextStyle(
                      color: c.pinkStrong,
                      fontWeight: FontWeight.w700,
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Text(
              'Confeitaria feita à mão, do forno para a sua mesa.',
              style: display(context, size: 38, height: 1.05),
            ),
            const SizedBox(height: 14),
            Text(
              'Cookies, tortas, brownies e bolos preparados em pequenas fornadas, com ingredientes selecionados e muito cuidado em cada detalhe.',
              style: TextStyle(color: c.muted, fontSize: 17, height: 1.45),
            ),
            const SizedBox(height: 22),
            KeyedSubtree(
              key: _heroCta,
              child: BusyButton(
                label: 'Fazer pedido',
                icon: Icons.arrow_forward_rounded,
                onPressed: _order,
              ),
            ),
            const SizedBox(height: 10),
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: _scrollToMenu,
                style: TextButton.styleFrom(minimumSize: const Size(0, 48)),
                child: const Text('Ver cardápio'),
              ),
            ),
            const SizedBox(height: 26),
            SizedBox(
              height: 320,
              child: FutureBuilder<Menu>(
                future: _menu,
                builder: (context, snap) {
                  final api = context.read<Session>().customerApi;
                  // sem foto própria no cardápio ainda: usa as fotos de vitrine já capturadas, em vez de deixar sem foto nenhuma
                  final photos = _assignPhotos(snap.data, api, const ['bolo', 'cookie', 'brownie'], <String>{});
                  final cookie = photos['cookie'] ?? 'https://raykookie.kntap.com.br/fotos/cookie-destaque.jpg';
                  final brownie = photos['brownie'] ?? 'https://raykookie.kntap.com.br/fotos/brownie-destaque.jpg';
                  return Row(
                    children: [
                      Expanded(
                        child: PhotoTile(
                          kind: 'bolo',
                          url: photos['bolo'],
                          radius: 26,
                          aspectRatio: null,
                          size: null,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          children: [
                            Expanded(child: PhotoTile(kind: 'cookie', url: cookie, radius: 26)),
                            const SizedBox(height: 10),
                            Expanded(child: PhotoTile(kind: 'brownie', url: brownie, radius: 26)),
                          ],
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _highlights(BuildContext context) {
    final c = context.c;
    final items = [
      (
        Icons.favorite_border_rounded,
        'Feito à mão',
        'Cada receita é preparada do zero, sem pressa.',
      ),
      (
        Icons.grass_rounded,
        'Ingredientes selecionados',
        'Chocolate de verdade, manteiga e frutas frescas.',
      ),
      (
        Icons.event_available_outlined,
        'Sob encomenda',
        'Peça com pelo menos ${AppConfig.leadDaysLabel}.',
      ),
    ];
    return Container(
      color: c.surface,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
      child: Centered(
        child: Column(
          children: [
            for (final (icon, title, text) in items)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 44,
                      height: 44,
                      decoration: BoxDecoration(
                        color: c.pinkTint,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: Icon(icon, color: c.pinkStrong),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            title,
                            style: const TextStyle(
                              fontWeight: FontWeight.w800,
                              fontSize: 16,
                            ),
                          ),
                          Text(
                            text,
                            style: TextStyle(color: c.muted, height: 1.35),
                          ),
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
      padding: const EdgeInsets.fromLTRB(20, 40, 20, 40),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const PhotoTile(kind: 'bancada', aspectRatio: 4 / 3, radius: 26),
            const SizedBox(height: 22),
            Text(
              'NOSSA HISTÓRIA',
              style: TextStyle(
                color: c.pinkStrong,
                fontWeight: FontWeight.w800,
                fontSize: 13,
                letterSpacing: 1.2,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'Doces simples, feitos com capricho.',
              style: display(context, size: 30),
            ),
            const SizedBox(height: 12),
            Text(
              'A Raykookie nasceu na cozinha de casa, com uma receita de cookie e a vontade de adoçar o dia de quem a gente gosta. Hoje continuamos assim: fornadas pequenas, receitas que testamos até ficarem perfeitas e atenção a cada pedido.',
              style: TextStyle(color: c.muted, height: 1.5),
            ),
            const SizedBox(height: 10),
            Text(
              'Trabalhamos só sob encomenda, para que tudo chegue fresquinho. Escolha o que você quer, diga o dia e o resto é com a gente.',
              style: TextStyle(color: c.muted, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }

  Widget _gallery(BuildContext context) {
    final c = context.c;
    const shots = [
      ('cookie', 'Cookies recém-saídos do forno'),
      ('bolo', 'Bolo decorado para festa'),
      ('brownie', 'Brownies com casquinha crocante'),
      ('torta', 'Torta de limão maçaricada'),
      ('embalagem', 'Caixa de presente'),
      ('bancada', 'Nossa bancada de trabalho'),
    ];
    return Container(
      color: c.surface2,
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 40),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Text(
              'Um gostinho do que fazemos',
              style: display(context, size: 30),
            ),
          ),
          const SizedBox(height: 18),
          SizedBox(
            height: 220,
            child: FutureBuilder<Menu>(
              future: _menu,
              builder: (context, snap) {
                final api = context.read<Session>().customerApi;
                final used = <String>{};
                _assignPhotos(snap.data, api, const ['bolo', 'cookie', 'brownie'], used); // mesmas fotos do topo: não repete aqui
                final photos = _assignPhotos(snap.data, api, [for (final s in shots) s.$1], used);
                return ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  itemCount: shots.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 12),
                  itemBuilder: (_, i) => SizedBox(
                    width: 260,
                    child: PhotoTile(kind: shots[i].$1, url: photos[shots[i].$1], radius: 26, size: null),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _menuSection(BuildContext context) {
    final c = context.c;
    return Padding(
      key: _cardapio,
      padding: const EdgeInsets.fromLTRB(20, 40, 20, 40),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'CARDÁPIO',
              style: TextStyle(
                color: c.pinkStrong,
                fontWeight: FontWeight.w800,
                fontSize: 13,
                letterSpacing: 1.2,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'O que tem para hoje (e para o seu dia)',
              style: display(context, size: 30),
            ),
            const SizedBox(height: 12),
            const ShareMenuButton(),
            const SizedBox(height: 16),
            FutureBuilder<Menu>(
              future: _menu,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done)
                  return const Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(child: CircularProgressIndicator()),
                  );
                if (snap.hasError) {
                  return Column(
                    children: [
                      Text(
                        'Não conseguimos carregar o cardápio agora.',
                        style: TextStyle(color: c.muted),
                      ),
                      const SizedBox(height: 10),
                      BusyButton(
                        label: 'Tentar de novo',
                        expand: false,
                        outlined: true,
                        onPressed: () => setState(() => _menu = _loadMenu()),
                      ),
                    ],
                  );
                }
                final menu = snap.data!;
                if (menu.groups.isEmpty) {
                  return Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(28),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: c.line),
                    ),
                    child: Text(
                      'Nosso cardápio está sendo preparado. Volte em breve!',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: c.muted),
                    ),
                  );
                }
                final shown = [
                  for (final g in menu.groups)
                    if (_group == null || g.id == _group) g,
                ];
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (menu.groups.length > 1)
                      SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: Row(
                          children: [
                            _chip(
                              context,
                              'Todos',
                              _group == null,
                              () => setState(() => _group = null),
                            ),
                            for (final g in menu.groups)
                              _chip(
                                context,
                                g.name,
                                _group == g.id,
                                () => setState(() => _group = g.id),
                              ),
                          ],
                        ),
                      ),
                    for (final g in shown) ...[
                      if (menu.groups.length > 1 && _group == null)
                        Padding(
                          padding: const EdgeInsets.only(top: 18, bottom: 10),
                          child: Text(
                            g.name,
                            style: display(context, size: 24),
                          ),
                        ),
                      for (final m in g.items) _menuTile(context, m),
                    ],
                    const SizedBox(height: 12),
                    Text(
                      'Precisa de outro sabor, tamanho ou decoração? Conte no campo de observações do pedido.',
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _chip(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onTap,
  ) {
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) => onTap(),
        showCheckmark: false,
        backgroundColor: c.surface,
        selectedColor: c.ink,
        side: BorderSide(color: selected ? c.ink : c.line),
        labelStyle: TextStyle(
          color: selected ? c.bg : c.ink,
          fontWeight: FontWeight.w700,
          fontSize: 14,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
        materialTapTargetSize: MaterialTapTargetSize.padded,
      ),
    );
  }

  Widget _menuTile(BuildContext context, MenuItem m) {
    final c = context.c;
    final api = context.read<Session>().customerApi;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: c.line),
      ),
      child: Row(
        children: [
          PhotoTile(
            kind: photoKind(m.name, m.category),
            url: m.image == null ? null : api.absolute(m.image!),
            size: 76,
            radius: 18,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  m.name,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 16,
                    height: 1.2,
                  ),
                ),
                if (m.category.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      m.category,
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                  ),
                if (weightLabel(m.weightG).isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text('${weightLabel(m.weightG)} cada', style: TextStyle(color: c.muted, fontSize: 13)),
                  ),
                if (m.tags.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Wrap(
                      spacing: 4,
                      runSpacing: 4,
                      children: [
                        for (final t in m.tags)
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                            decoration: BoxDecoration(color: c.pinkTint, borderRadius: BorderRadius.circular(99)),
                            child: Text(menuTagLabel(t), style: TextStyle(color: c.pinkStrong, fontWeight: FontWeight.w700, fontSize: 11)),
                          ),
                      ],
                    ),
                  ),
                const SizedBox(height: 4),
                Text(money(m.price), style: display(context, size: 19)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Semantics(
            button: true,
            label: 'Pedir ${m.name}',
            child: Material(
              color: c.pink,
              borderRadius: BorderRadius.circular(16),
              child: InkWell(
                borderRadius: BorderRadius.circular(16),
                onTap: () => context.push('/cliente/pedidos/novo?item=${m.id}'),
                child: SizedBox(
                  width: 48,
                  height: 48,
                  child: Icon(Icons.add_rounded, color: c.onPink, size: 26),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _reviews(BuildContext context) {
    final c = context.c;
    const reviews = [
      (
        'Mariana S.',
        'Aniversário de 30 anos',
        'O bolo chegou lindo e estava ainda mais gostoso do que parecia. Todo mundo na festa pediu o contato!',
      ),
      (
        'Carlos e Ana',
        'Presente de casamento',
        'Pedimos a caixa de brownies de presente e foi sucesso. Embalagem caprichada, entrega pontual.',
      ),
      (
        'Patrícia L.',
        'Cliente frequente',
        'Os cookies de Ninho com Nutella viciam. Já virou tradição de sexta-feira aqui em casa.',
      ),
    ];
    return Container(
      color: c.surface2,
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 40, 20, 40),
      child: Centered(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Quem já provou, recomenda',
              style: display(context, size: 30),
            ),
            const SizedBox(height: 16),
            for (final (name, occasion, text) in reviews)
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(bottom: 12),
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: c.surface,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        for (var i = 0; i < 5; i++)
                          const Icon(
                            Icons.star_rounded,
                            color: Color(0xFFF0A020),
                            size: 20,
                          ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Text(
                      '“$text”',
                      style: const TextStyle(height: 1.45, fontSize: 15),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      name,
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                    Text(
                      occasion,
                      style: TextStyle(color: c.muted, fontSize: 13),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _feedback(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
      child: const Centered(maxWidth: 560, child: FeedbackCard()),
    );
  }

  Widget _instagram(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
      child: Centered(
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(24),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => launchUrl(
              Uri.parse('https://www.instagram.com/raykookies'),
              mode: LaunchMode.externalApplication,
            ),
            child: Ink(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF)],
                ),
              ),
              padding: const EdgeInsets.all(18),
              child: Row(
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white.withValues(alpha: 0.6), width: 3),
                    ),
                    child: const RkLogo(variant: 'simbolo', height: 48),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.camera_alt_rounded, size: 15, color: Colors.white),
                            const SizedBox(width: 6),
                            Text(
                              'INSTAGRAM',
                              style: TextStyle(
                                color: Colors.white.withValues(alpha: 0.9),
                                fontWeight: FontWeight.w800,
                                fontSize: 12,
                                letterSpacing: 0.6,
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '@raykookies',
                          style: display(context, size: 20, color: Colors.white),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Fotos novas e bastidores da confeitaria. Vem seguir!',
                          style: TextStyle(color: Colors.white.withValues(alpha: 0.9), fontSize: 13, height: 1.3),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _orderArea(BuildContext context) {
    const steps = [
      ('1', 'Entre ou crie sua conta', 'Com o Google ou com e-mail e senha.'),
      (
        '2',
        'Escolha do cardápio',
        'Monte o pedido e diga quando quer receber.',
      ),
      ('3', 'Receba ou retire', 'Acompanhe tudo pelo app. Só chamamos no WhatsApp se for necessário.'),
    ];
    final c = context.c;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 40, 16, 40),
      child: Centered(
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.all(24),
          decoration: BoxDecoration(
            color: AppColors.rkEscuro,
            borderRadius: BorderRadius.circular(32),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Faça sua encomenda',
                style: display(context, size: 32, color: Colors.white),
              ),
              const SizedBox(height: 8),
              const Text(
                'Em poucos minutos o pedido está feito, e você acompanha todo o andamento pelo app, na sua conta.',
                style: TextStyle(color: Color(0xBFFFFFFF), height: 1.4),
              ),
              const SizedBox(height: 18),
              BusyButton(
                label: 'Fazer pedido',
                icon: Icons.arrow_forward_rounded,
                onPressed: _order,
              ),
              const SizedBox(height: 20),
              for (final (n, t, d) in steps)
                Container(
                  margin: const EdgeInsets.only(bottom: 10),
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: const Color(0x14FFFFFF),
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        width: 34,
                        height: 34,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: c.pink,
                          shape: BoxShape.circle,
                        ),
                        child: Text(
                          n,
                          style: TextStyle(
                            color: c.onPink,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              t,
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            Text(
                              d,
                              style: const TextStyle(
                                color: Color(0xB3FFFFFF),
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _footer(BuildContext context) {
    final c = context.c;
    return Container(
      width: double.infinity,
      color: c.surface,
      padding: const EdgeInsets.fromLTRB(20, 32, 20, 32),
      child: Column(
        children: [
          const RkLogo(height: 34),
          const SizedBox(height: 12),
          Text(
            'Confeitaria feita à mão · Encomendas com ${AppConfig.leadDaysLabel}.',
            textAlign: TextAlign.center,
            style: TextStyle(color: c.muted, fontSize: 13),
          ),
          const SizedBox(height: 4),
          LegalLinks(onOpen: (id) => context.push('/$id')),
          const SizedBox(height: 6),
          const VersionLabel('Raykookie'),
        ],
      ),
    );
  }
}
