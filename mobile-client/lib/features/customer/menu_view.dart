import 'package:raykookie_common/core/models.dart';

/// Só apresentação do cardápio (nada grava no banco), igual ao `lib/menu-view.ts` do site: quando o nome de um grupo não
/// descreve o que há dentro (ex.: brownies dentro de "Bolos"), a vitrine mostra o nome certo e junta os grupos que acabam
/// com o mesmo nome. Se o dono arrumar as categorias no painel, nada aqui muda nada.

const _accents = {
  'á': 'a', 'à': 'a', 'â': 'a', 'ã': 'a', 'ä': 'a', 'é': 'e', 'è': 'e', 'ê': 'e', 'í': 'i', 'ì': 'i', 'î': 'i',
  'ó': 'o', 'ò': 'o', 'ô': 'o', 'õ': 'o', 'ö': 'o', 'ú': 'u', 'ù': 'u', 'û': 'u', 'ü': 'u', 'ç': 'c',
};

String _norm(String s) => s.toLowerCase().split('').map((ch) => _accents[ch] ?? ch).join();
String _slug(String s) => _norm(s).replaceAll(RegExp(r'[^a-z0-9]+'), '-').replaceAll(RegExp(r'^-|-$'), '');

enum _Kind { brownie, cookie, bolo }

const _label = {_Kind.brownie: 'Brownies', _Kind.cookie: 'Cookies', _Kind.bolo: 'Bolos e tortas'};
final _stem = {_Kind.brownie: RegExp('brownie'), _Kind.cookie: RegExp('cookie|biscoito'), _Kind.bolo: RegExp('bolo|torta')};

/// O que o item é, olhando só o nome (a categoria é justamente o que pode estar fora do lugar).
_Kind? _kindOfName(String name) {
  final t = _norm(name);
  if (RegExp(r'brownie|\bbits\b').hasMatch(t)) return _Kind.brownie;
  if (RegExp('cookie|biscoito').hasMatch(t)) return _Kind.cookie;
  if (RegExp('bolo|torta|cupcake').hasMatch(t)) return _Kind.bolo;
  return null;
}

/// Grupos como a vitrine mostra: nomes coerentes com o conteúdo e sem grupos repetidos.
List<MenuGroup> presentGroups(Menu menu) {
  final out = <MenuGroup>[];
  for (final g in menu.groups) {
    var name = g.name;
    if (g.items.isNotEmpty && !g.items.any((i) => i.combo != null)) {
      final kinds = {for (final i in g.items) _kindOfName(i.name)};
      final only = kinds.length == 1 ? kinds.first : null;
      if (only != null && !_stem[only]!.hasMatch(_norm(g.name))) name = _label[only]!;
    }
    final id = name == g.name ? g.id : _slug(name);
    final i = out.indexWhere((o) => o.id == id);
    if (i >= 0) {
      out[i] = MenuGroup(id: id, name: out[i].name, items: [...out[i].items, ...g.items]);
    } else {
      out.add(MenuGroup(id: id, name: name, items: [...g.items]));
    }
  }
  return out;
}

const _small = {'de', 'da', 'do', 'das', 'dos', 'e', 'com', 'em'};

/// "AMOR DE NUTELLA" → "Amor de Nutella". Nomes que já vêm com minúsculas ficam como estão.
String niceName(String name) {
  if (name != name.toUpperCase() || name == name.toLowerCase()) return name;
  final words = name.toLowerCase().split(' ');
  return [
    for (var i = 0; i < words.length; i++)
      (i > 0 && _small.contains(words[i]) || words[i].isEmpty) ? words[i] : words[i][0].toUpperCase() + words[i].substring(1),
  ].join(' ');
}
