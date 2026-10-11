import 'config.dart';
import 'format.dart';
import 'models.dart';

/// Regras da loja online. Porte de shared/shop.ts: o servidor valida de novo, aqui é só para avisar logo.
class Choice {
  const Choice(this.id, this.label, [this.hint = '']);
  final String id, label, hint;
}

const fulfillmentOptions = [
  Choice('retirada', 'Retirar no local', 'Sem taxa'),
  Choice('entrega', 'Receber em casa', 'Se houver taxa de entrega, avisamos pelo WhatsApp'),
];
const paymentMethods = [
  Choice('pix', 'Pix', 'Geramos o Pix copia e cola ao finalizar o pedido.'),
  Choice('cartao', 'Cartão', 'Cartão de crédito, com segurança.'),
  Choice('dinheiro', 'Dinheiro', 'Informe se precisa de troco.'),
];
String paymentLabel(String id) => paymentMethods.where((p) => p.id == id).map((p) => p.label).firstOrNull ?? id;
/// Parcelamento sem juros (a loja absorve a taxa do Mercado Pago) só a partir deste valor; abaixo, só 1x.
const cardInstallmentsMinAmount = 150;
const maxInstallments = 12;
const timeWindows = [
  Choice('manha', 'Manhã (8h às 12h)'),
  Choice('tarde', 'Tarde (12h às 18h)'),
  Choice('noite', 'Noite (18h às 21h)'),
];
const maxQty = 50;

/// Status que o cliente enxerga: (rótulo, tom).
const customerStatus = {
  'novo': ('Recebido', 'neutral'),
  'producao': ('Em preparo', 'warn'),
  'pronto': ('Pronto', 'ok'),
  'entregue': ('Entregue', 'ok'),
  'cancelado': ('Cancelado', 'bad'),
};

/// Status e pagamento no painel (id, rótulo, tom).
const orderStatus = [
  ('novo', 'Novo', 'pink'),
  ('producao', 'Em produção', 'warn'),
  ('pronto', 'Pronto', 'ok'),
  ('entregue', 'Entregue', 'neutral'),
  ('cancelado', 'Cancelado', 'bad'),
];
const orderPayment = [
  ('pendente', 'Pendente', 'bad'),
  ('sinal', 'Sinal pago', 'warn'),
  ('pago', 'Pago', 'ok'),
];

const _rank = ['novo', 'producao', 'pronto', 'entregue'];

/// Situação geral: a do item menos adiantado (ignorando cancelados).
String overallStatus(List<String>? statuses) {
  final s = (statuses ?? []).where((e) => e.isNotEmpty).toList();
  final live = s.where((e) => e != 'cancelado').toList();
  if (live.isEmpty) return 'cancelado';
  return live.reduce((a, b) => _rank.indexOf(a) <= _rank.indexOf(b) ? a : b);
}

/// Qual ilustração usar quando a receita não tem foto: cookie, torta, brownie, bolo ou bancada.
String photoKind(String name, String category) {
  var t = '$name $category'.toLowerCase();
  const from = 'áàâãäéèêëíìîïóòôõöúùûüç';
  const to = 'aaaaaeeeeiiiiooooouuuuc';
  for (var i = 0; i < from.length; i++) {
    t = t.replaceAll(from[i], to[i]);
  }
  if (RegExp(r'cookie|biscoito').hasMatch(t)) return 'cookie';
  if (RegExp(r'torta|pie|tarte').hasMatch(t)) return 'torta';
  if (RegExp(r'brownie|brigadeiro|docinho').hasMatch(t)) return 'brownie';
  if (RegExp(r'bolo|cupcake').hasMatch(t)) return 'bolo';
  return 'bancada';
}

double cartTotal(Map<String, int> cart, List<MenuItem> menu) {
  var total = 0.0;
  cart.forEach((id, qty) {
    final m = menu.where((x) => x.id == id);
    if (m.isNotEmpty) total += m.first.price * qty;
  });
  return total;
}

bool isEmail(String v) => v.length <= 120 && RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]{2,}$').hasMatch(v);

class OrderForm {
  OrderForm({
    this.contactName = '', this.phone = '', this.fulfillment = 'retirada', this.cep = '', this.street = '', this.number = '',
    this.complement = '', this.district = '', this.city = '', this.scheduledFor = '', this.timeWindow = '', this.paymentMethod = '',
    this.changeFor = '', this.notes = '',
  });
  String contactName, phone, fulfillment, cep, street, number, complement, district, city, scheduledFor, timeWindow, paymentMethod, changeFor, notes;
  // só preenchidos quando paymentMethod == 'cartao'; cardToken/cpf nunca são guardados no servidor (ver shared/legal.ts)
  String cardToken = '', cardPaymentMethodId = '', cpf = '';
  /// Emissor do cartão (vazio = não reconhecido) e device ID do antifraude do Mercado Pago (vazio = não obtido).
  String cardIssuerId = '', deviceId = '';
  /// Cupom aplicado no último passo (vazio = nenhum); o servidor confere de novo ao criar o pedido.
  String couponCode = '';
  int installments = 1;

  Map<String, dynamic> toJson(Map<String, int> cart) => {
        'items': [for (final e in cart.entries.where((e) => e.value > 0)) {'id': e.key, 'qty': e.value}],
        'contact_name': contactName, 'phone': phone, 'fulfillment': fulfillment, 'cep': cep, 'street': street, 'number': number,
        'complement': complement, 'district': district, 'city': city, 'scheduled_for': scheduledFor, 'time_window': timeWindow,
        'payment_method': paymentMethod, 'change_for': changeFor, 'notes': notes,
        if (couponCode.isNotEmpty) 'coupon_code': couponCode,
        if (paymentMethod == 'cartao') ...{
          'card_token': cardToken, 'card_payment_method_id': cardPaymentMethodId, 'cpf': cpf, 'installments': installments,
          if (cardIssuerId.isNotEmpty) 'card_issuer_id': cardIssuerId,
          if (deviceId.isNotEmpty) 'device_id': deviceId,
        },
      };
}

/// Quantas etapas tem o pedido no app: itens, dados, entrega, pagamento e observações.
const orderSteps = ['Itens', 'Seus dados', 'Entrega', 'Pagamento', 'Observações'];

/// Confere só a etapa [step] (0 a 4) do pedido. Devolve a mensagem de erro, ou null se a etapa está certa.
String? validateOrderStep(int step, OrderForm f, Map<String, int> cart, List<MenuItem> menu, {String? today}) {
  switch (step) {
    case 0:
      final lines = cart.entries.where((e) => e.value > 0).toList();
      if (lines.isEmpty) return 'Escolha pelo menos um item do cardápio.';
      for (final l in lines) {
        if (!menu.any((m) => m.id == l.key)) return 'Um dos itens escolhidos não está mais no cardápio.';
        if (l.value < 1 || l.value > maxQty) return 'A quantidade de cada item vai de 1 a $maxQty.';
      }
      return null;
    case 1:
      if (f.contactName.trim().length < 2) return 'Informe seu nome.';
      if (onlyDigits(f.phone, 11).length < 10) return 'Informe um telefone com DDD. Só usamos se precisarmos falar com você sobre o pedido.';
      return null;
    case 2:
      if (f.fulfillment != 'retirada' && f.fulfillment != 'entrega') return 'Escolha se você vai retirar ou receber em casa.';
      if (f.fulfillment == 'entrega') {
        if (onlyDigits(f.cep, 8).length != 8) return 'Informe o CEP com 8 números.';
        if (f.street.trim().isEmpty) return 'Informe a rua do endereço de entrega.';
        if (f.number.trim().isEmpty) return 'Informe o número (ou S/N, se não tiver).';
        if (f.district.trim().isEmpty) return 'Informe o bairro.';
        if (f.city.trim().isEmpty) return 'Informe a cidade.';
      }
      if (f.scheduledFor.isEmpty) return 'Escolha a data desejada.';
      final t = today ?? todayIso();
      if (f.scheduledFor.compareTo(addDaysIso(t, AppConfig.leadDays)) < 0) {
        return 'Fazemos tudo sob encomenda: escolha uma data com pelo menos ${AppConfig.leadDaysLabel}.';
      }
      if (f.scheduledFor.compareTo(addDaysIso(t, 180)) > 0) return 'Escolha uma data nos próximos 6 meses.';
      if (!timeWindows.any((w) => w.id == f.timeWindow)) return 'Escolha o período do dia.';
      return null;
    case 3:
      if (!paymentMethods.any((p) => p.id == f.paymentMethod)) return 'Escolha a forma de pagamento.';
      if (f.paymentMethod == 'dinheiro' && f.changeFor.trim().isNotEmpty) {
        final n = parseNum(f.changeFor);
        if (n <= 0 || n > 100000) return 'O valor do troco não é válido.';
        if (n < cartTotal(cart, menu)) return 'O troco precisa ser para um valor maior ou igual ao total do pedido.';
      }
      if (f.paymentMethod == 'cartao') {
        if (f.cardToken.isEmpty) return 'Preencha os dados do cartão.';
        if (f.cardPaymentMethodId.isEmpty) return 'Não reconhecemos a bandeira desse cartão.';
        if (!isValidCpf(f.cpf)) return 'Informe um CPF válido (exigido pelo Mercado Pago para pagar com cartão).';
        if (f.installments < 1 || f.installments > maxInstallments) return 'Escolha uma quantidade de parcelas válida.';
        if (f.installments > 1 && cartTotal(cart, menu) <= cardInstallmentsMinAmount) return 'Parcelamento só para pedidos acima de R\$ $cardInstallmentsMinAmount.';
      }
      return null;
    default:
      return null;
  }
}

/// Confere o pedido inteiro antes de enviar. Devolve a mensagem de erro, ou null se estiver tudo certo.
String? validateOrder(OrderForm f, Map<String, int> cart, List<MenuItem> menu, {String? today}) {
  for (var i = 0; i < orderSteps.length; i++) {
    final e = validateOrderStep(i, f, cart, menu, today: today);
    if (e != null) return e;
  }
  return null;
}

/// Gramatura de uma unidade para o cardápio: "80 g", "1,5 kg". Vazio se não informada. Porte de weightLabel (shared/shop.ts).
String weightLabel(double g) {
  if (g <= 0) return '';
  String n(double v) => ((v * 100).round() / 100).toString().replaceAll(RegExp(r'\.0$'), '').replaceAll('.', ',');
  return g >= 1000 ? '${n(g / 1000)} kg' : '${n(g)} g';
}

/// Rótulo de uma característica do cardápio (ver editor no painel). Porte de menuTagLabel (shared/menu-tags.ts) —
/// mesma lista fixa de ids nos dois lados.
const Map<String, String> _menuTagLabels = {
  'vegano': 'Vegano',
  'vegetariano': 'Vegetariano',
  'sem_gluten': 'Sem glúten',
  'sem_lactose': 'Sem lactose',
  'contem_gluten': 'Contém glúten',
  'contem_leite': 'Contém leite',
  'contem_ovos': 'Contém ovos',
  'contem_castanhas': 'Contém castanhas',
  'contem_amendoim': 'Contém amendoim',
  'contem_soja': 'Contém soja',
};
String menuTagLabel(String id) => _menuTagLabels[id] ?? id;

/// Ordem fixa dos ids, para o editor de cardápio do painel.
List<String> get menuTagIds => _menuTagLabels.keys.toList(growable: false);
