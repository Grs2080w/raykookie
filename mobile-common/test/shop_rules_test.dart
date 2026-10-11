import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/format.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_common/core/shop_rules.dart';

// Espelha backend/tests/shop.test.ts.
void main() {
  final menu = [
    MenuItem(id: '1', name: 'Cookie tradicional', price: 9, category: 'Cookies e biscoitos'),
    MenuItem(id: '2', name: 'Caixa com 6 brownies', price: 62, category: 'Brigadeiros e docinhos'),
    MenuItem(id: '3', name: 'Bolo de chocolate', price: 98, category: 'Bolos'),
  ];
  const today = '2026-10-01';
  OrderForm base() => OrderForm(contactName: 'Maria Souza', phone: '(11) 91234-5678', scheduledFor: '2026-10-05', timeWindow: 'tarde', paymentMethod: 'pix');
  String? check(OrderForm f, Map<String, int> cart) => validateOrder(f, cart, menu, today: today);

  group('validateOrder', () {
    test('pedido certo passa', () => expect(check(base(), {'1': 4, '2': 1}), isNull));
    test('recusa item inexistente, quantidade inválida e carrinho vazio', () {
      expect(check(base(), {'x': 1}), contains('cardápio'));
      expect(check(base(), {'1': 51}), contains('quantidade'));
      expect(check(base(), {}), contains('pelo menos um item'));
    });
    test('exige antecedência mínima e data', () {
      expect(check(base()..scheduledFor = '2026-10-01', {'1': 1}), contains('antecedência'));
      expect(check(base()..scheduledFor = '2026-10-02', {'1': 1}), isNull);
      expect(check(base()..scheduledFor = '', {'1': 1}), contains('data'));
    });
    test('entrega exige endereço completo', () {
      expect(check(base()..fulfillment = 'entrega', {'1': 1}), contains('CEP'));
      final f = base()..fulfillment = 'entrega'..cep = '01310-100'..street = 'Av. Paulista'..number = '1000'..district = 'Bela Vista'..city = 'São Paulo';
      expect(check(f, {'1': 1}), isNull);
    });
    test('valida telefone, pagamento e troco', () {
      expect(check(base()..phone = '123', {'1': 1}), contains('telefone'));
      expect(check(base()..paymentMethod = 'fiado', {'1': 1}), contains('pagamento'));
      expect(check(base()..paymentMethod = 'dinheiro'..changeFor = '50', {'1': 4, '2': 1}), contains('troco')); // total 98
      expect(check(base()..paymentMethod = 'dinheiro'..changeFor = '100,00', {'1': 4, '2': 1}), isNull);
    });
  });

  group('validateOrder — pagamento com cartão', () {
    const cpf = '529.982.247-25'; // CPF válido de teste (mesmo do backend/tests/cpf.test.ts)
    OrderForm card() => base()
      ..paymentMethod = 'cartao'
      ..cardToken = 'tok_123'
      ..cardPaymentMethodId = 'master'
      ..cpf = cpf
      ..installments = 1;
    test('exige token, bandeira e CPF válido', () {
      expect(check(card()..cardToken = '', {'1': 4, '2': 1}), contains('dados do cartão'));
      expect(check(card()..cardPaymentMethodId = '', {'1': 4, '2': 1}), contains('bandeira'));
      expect(check(card()..cpf = '111.111.111-11', {'1': 4, '2': 1}), contains('CPF'));
    });
    test('parcela só acima de R\$150, e só até o limite', () {
      // total deste carrinho (item 1, qty 4 + item 2, qty 1) = 4*9 + 62 = 98 (abaixo de 150)
      expect(check(card()..installments = 2, {'1': 4, '2': 1}), contains('150'));
      expect(check(card()..installments = 1, {'1': 4, '2': 1}), isNull);
      expect(check(card()..installments = 0, {'1': 4, '2': 1}), contains('parcelas'));
      expect(check(card()..installments = 13, {'1': 4, '2': 1}), contains('parcelas'));
    });
    test('acima de R\$150, aceita parcelar', () {
      expect(check(card()..installments = 3, {'3': 2}), isNull); // 2×98 = 196
    });
  });

  group('auxiliares', () {
    test('máscaras', () {
      expect(formatPhone('11912345678'), '(11) 91234-5678');
      expect(formatPhone('1133334444'), '(11) 3333-4444');
      expect(formatPhone('11a9'), '(11) 9');
      expect(formatCep('01310100'), '01310-100');
    });
    test('total do carrinho', () => expect(cartTotal({'1': 2, 'nao-existe': 9}, menu), 18));
    test('situação geral usa o item menos adiantado e ignora cancelados', () {
      expect(overallStatus(['pronto', 'producao']), 'producao');
      expect(overallStatus(['entregue', 'cancelado']), 'entregue');
      expect(overallStatus(['cancelado']), 'cancelado');
      expect(overallStatus([]), 'cancelado');
    });
    test('ilustração padrão pelo nome ou categoria', () {
      expect(photoKind('Cookie de Ninho', 'Outros'), 'cookie');
      expect(photoKind('Tarte de limão', 'Outros'), 'torta');
      expect(photoKind('Delícia', 'Bolos no pote'), 'bolo');
      expect(photoKind('Pão de mel', 'Outros'), 'bancada');
    });
  });

  group('números', () {
    test('parseNum aceita vírgula, ponto e milhar', () {
      expect(parseNum('12,5'), 12.5);
      expect(parseNum('12.5'), 12.5);
      expect(parseNum('1.500,50'), 1500.5);
      expect(parseNum('1.000'), 1000); // 1 kg = 1.000 g
      expect(parseNum('0.500'), 0.5);
      expect(parseNum('10.25'), 10.25);
      expect(parseNum(fieldNum(2.125)), 2.125);
      expect(parseNum('abc'), 0);
      expect(parseNum(''), 0);
    });
    test('sanitizeNumber descarta o que não é número', () {
      expect(sanitizeNumber('1a2;3'), '123');
      expect(sanitizeNumber('R\$ 7,5,0'), '7,50');
      expect(sanitizeNumber('1,5.0'), '1,50');
      expect(sanitizeNumber('5,5a', integer: true), '55');
      expect(sanitizeNumber('1234567890123456789').length, 15);
    });
    test('datas', () {
      expect(dateBR('2026-10-05'), '05/10/2026');
      expect(dateBR(null), '—');
      expect(addDaysIso('2026-10-30', 3), '2026-11-02');
    });
    test('moeda em pt-BR', () {
      expect(money(1234.5), 'R\$ 1.234,50');
      expect(moneyPrecise(0.0059), 'R\$ 0,0059');
    });
  });

  test('weightLabel formata a gramatura da unidade', () {
    expect(weightLabel(80), '80 g');
    expect(weightLabel(62.5), '62,5 g');
    expect(weightLabel(1500), '1,5 kg');
    expect(weightLabel(0), '');
  });

  test('pedido: coupon_code só vai quando há cupom aplicado', () {
    final f = OrderForm()..paymentMethod = 'pix';
    expect(f.toJson({'1': 2}).containsKey('coupon_code'), isFalse);
    f.couponCode = 'NATAL20';
    expect(f.toJson({'1': 2})['coupon_code'], 'NATAL20');
  });
}
