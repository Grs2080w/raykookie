/// Catálogo da medição de engajamento (espelho de `shared/engagement.ts` e do servidor). Um teste compara este arquivo
/// com `backend-java/rules-vectors/engagement.json`, gerado do TypeScript: mudou um lado, os três têm que mudar.
class EngagementRule {
  const EngagementRule({this.route = false, this.item = false, this.qty = false, this.detailRequired = false, this.details = const []});

  /// O evento usa (e exige) rota, item do cardápio e quantidade.
  final bool route, item, qty;

  /// O campo `detail` é obrigatório.
  final bool detailRequired;

  /// Valores aceitos em `detail`.
  final List<String> details;
}

const _referrers = ['direct', 'instagram', 'whatsapp', 'search', 'other'];
const _surfaces = ['landing', 'cardapio', 'home_app'];
const _steps = ['items', 'data', 'delivery', 'payment', 'extras'];
const _methods = ['pix', 'cartao', 'dinheiro'];
const _coupons = ['ok', 'invalid', 'min_total', 'inactive', 'used_up'];
const _submits = ['ok', 'validation', 'card_refused', 'network', 'other'];
const _threeDs = ['authenticated', 'closed', 'failed'];

/// Todos os eventos aceitos. Evento fora desta lista não é enviado.
const Map<String, EngagementRule> engagementEvents = {
  'screen_view': EngagementRule(route: true, details: _referrers),
  'menu_item_view': EngagementRule(item: true, detailRequired: true, details: _surfaces),
  'menu_item_click': EngagementRule(item: true, detailRequired: true, details: _surfaces),
  'cart_add': EngagementRule(item: true, qty: true),
  'cart_remove': EngagementRule(item: true, qty: true),
  'checkout_step': EngagementRule(detailRequired: true, details: _steps),
  'payment_method_select': EngagementRule(detailRequired: true, details: _methods),
  'coupon_attempt': EngagementRule(detailRequired: true, details: _coupons),
  'checkout_submit': EngagementRule(detailRequired: true, details: _submits),
  'card_3ds': EngagementRule(detailRequired: true, details: _threeDs),
  'order_placed': EngagementRule(detailRequired: true, details: _methods),
};

/// Motivo do cupom recusado, pela mensagem do servidor (o código digitado nunca é enviado).
String couponReason(String message) {
  final t = message.toLowerCase();
  if (RegExp(r'm[ií]nim|a partir de|acima de').hasMatch(t)) return 'min_total';
  if (RegExp(r'inativ|desativ').hasMatch(t)) return 'inactive';
  if (RegExp(r'esgot|já foi usado|ja foi usado|limite').hasMatch(t)) return 'used_up';
  return 'invalid';
}
