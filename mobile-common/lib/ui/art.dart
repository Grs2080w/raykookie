// Ilustrações usadas quando a receita não tem foto (mesmos desenhos do web).

String _wrap(String body) => '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 120">$body</svg>';

String _cookie() {
  const chips = [[40, 42], [70, 35], [82, 62], [58, 62], [36, 72], [66, 88], [90, 86], [48, 92], [100, 48]];
  final dots = [
    for (var i = 0; i < chips.length; i++)
      '<ellipse cx="${chips[i][0]}" cy="${chips[i][1]}" rx="7" ry="5.5" fill="#4a2a1c" transform="rotate(${i * 37} ${chips[i][0]} ${chips[i][1]})"/>'
  ].join();
  return _wrap('<defs><mask id="b"><rect width="120" height="120" fill="#fff"/><circle cx="97" cy="28" r="15" fill="#000"/></mask></defs>'
      '<circle cx="60" cy="62" r="48" fill="#c98b4e" mask="url(#b)"/><circle cx="60" cy="62" r="42" fill="#d79c5e" mask="url(#b)"/>$dots');
}

String _torta() {
  const b = [[45, 62], [62, 56], [78, 64], [56, 70]];
  final berries = [for (final p in b) '<circle cx="${p[0]}" cy="${p[1]}" r="6" fill="#e5475f"/>'].join();
  final leaves = [for (final p in b) '<path d="M${p[0] - 2} ${p[1] - 6}l2-4 2 4Z" fill="#3f9b5c"/>'].join();
  return _wrap('<ellipse cx="60" cy="86" rx="46" ry="12" fill="#c98b4e"/>'
      '<path d="M14 70c0-8 10-14 46-14s46 6 46 14v16c0 8-10 14-46 14S14 94 14 86Z" fill="#dba766"/>'
      '<ellipse cx="60" cy="70" rx="46" ry="14" fill="#fff6d6"/>'
      '<path d="M20 68c8-16 24-22 40-22s32 6 40 22c-10 10-26 14-40 14s-30-4-40-14Z" fill="#fff1b8"/>$berries$leaves');
}

String _brownie() => _wrap('<path d="M16 74 60 94l44-20v16L60 110 16 90Z" fill="#3a1f17"/>'
    '<path d="M16 58 60 38l44 20-44 20Z" fill="#6b3a26"/><path d="M16 58v16l44 20V78Z" fill="#4b2a1d"/>'
    '<path d="M104 58v16L60 94V78Z" fill="#3a1f17"/><path d="M22 58 60 41l38 17-38 17Z" fill="#5a3022"/>'
    '<path d="M30 57c10-4 14 4 24 0s16 4 26-1" stroke="#f3d9b1" stroke-width="3.5" fill="none" stroke-linecap="round"/>'
    '<rect x="44" y="55" width="5" height="5" rx="1" fill="#8a5a3c"/><rect x="68" y="51" width="5" height="5" rx="1" fill="#8a5a3c"/>'
    '<rect x="80" y="57" width="5" height="5" rx="1" fill="#8a5a3c"/>');

String _bolo() {
  const b = [[34, 56], [52, 53], [70, 54], [86, 57]];
  final cherries = [for (final p in b) '<circle cx="${p[0]}" cy="${p[1]}" r="4.5" fill="#e94b8a"/>'].join();
  return _wrap('<ellipse cx="60" cy="98" rx="44" ry="9" fill="#e0b5c6"/><path d="M20 58h80v36c0 6-18 10-40 10s-40-4-40-10Z" fill="#fff"/>'
      '<path d="M20 58h80v14c-8 4-14-2-20 4s-12 0-20 4-12-2-20 2-14 0-20-4Z" fill="#7a3f2a"/>'
      '<ellipse cx="60" cy="58" rx="40" ry="11" fill="#fff7fa"/><rect x="56" y="30" width="8" height="24" rx="2" fill="#f6c6d9"/>'
      '<path d="M60 18c4 5 4 9 0 12-4-3-4-7 0-12Z" fill="#f7a93b"/>$cherries');
}

String _bancada() => _wrap('<rect x="14" y="80" width="92" height="10" rx="4" fill="#c98b4e"/>'
    '<circle cx="36" cy="66" r="14" fill="#e3a465"/><circle cx="36" cy="66" r="9" fill="#c98b4e"/>'
    '<circle cx="62" cy="62" r="18" fill="#e94b8a"/><circle cx="62" cy="62" r="12" fill="#f7a8c7"/>'
    '<circle cx="90" cy="68" r="13" fill="#7a3f2a"/><circle cx="90" cy="68" r="8" fill="#9a5b3d"/>');

String _embalagem() => _wrap('<rect x="18" y="42" width="84" height="56" rx="6" fill="#fff"/>'
    '<rect x="18" y="42" width="84" height="16" rx="6" fill="#e94b8a"/><path d="M60 42v56" stroke="#f3b3cb" stroke-width="6"/>'
    '<path d="M60 42c-8-16-24-14-22-6s16 6 22 6Zm0 0c8-16 24-14 22-6s-16 6-22 6Z" fill="#e94b8a"/>');

String artSvg(String kind) {
  switch (kind) {
    case 'cookie':
      return _cookie();
    case 'torta':
      return _torta();
    case 'brownie':
      return _brownie();
    case 'bolo':
      return _bolo();
    case 'embalagem':
      return _embalagem();
    default:
      return _bancada();
  }
}

/// Cores de fundo (início, fim) de cada ilustração.
const artColors = {
  'cookie': (0xFFFBD9BD, 0xFFF3B98F),
  'torta': (0xFFFDEAB2, 0xFFF8D27A),
  'brownie': (0xFFE8C9D6, 0xFFD79BB5),
  'bolo': (0xFFFBD3E1, 0xFFF29BBD),
  'bancada': (0xFFFBE9F0, 0xFFF6C6D9),
  'embalagem': (0xFFFFE4D2, 0xFFF7BFA0),
};
