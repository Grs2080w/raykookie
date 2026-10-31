import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:raykookie_gestao/core/pix/venda_cart.dart';
import 'package:shared_preferences/shared_preferences.dart';

MenuItem _item(String id, String name, double price) => MenuItem(id: id, name: name, price: price, category: 'x');

void main() {
  test('soma por produto, total em centavos exatos e quantidade máxima', () {
    final cart = VendaCart();
    expect(cart.add(_item('a', 'Bolo', 0.1)), isTrue);
    cart.setQty('a', 3);
    expect(cart.totalCents, 30); // 3 × 0,10 sem erro de ponto flutuante
    for (var i = 0; i < 200; i++) {
      cart.add(_item('a', 'Bolo', 0.1));
    }
    expect(cart.lines.single.qty, VendaCart.maxQty);
  });

  test('produto sem preço não entra; quantidade 0 ou negativa tira o item', () {
    final cart = VendaCart();
    expect(cart.add(_item('z', 'Brinde', 0)), isFalse);
    expect(cart.isEmpty, isTrue);
    cart.add(_item('a', 'Bolo', 10));
    cart.setQty('a', 0);
    expect(cart.isEmpty, isTrue);
    cart.add(_item('a', 'Bolo', 10));
    cart.setQty('a', -2);
    expect(cart.isEmpty, isTrue);
  });

  test('volta igual depois de fechar e abrir o app (guardado no aparelho)', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final first = VendaCart(prefs)
      ..add(_item('a', 'Bolo', 12.5))
      ..add(_item('a', 'Bolo', 12.5))
      ..add(_item('b', 'Brigadeiro', 3.5));
    final reopened = VendaCart(prefs);
    expect(reopened.itemCount, first.itemCount);
    expect(reopened.totalCents, 2850);
    expect(reopened.summary(), '2× Bolo, 1× Brigadeiro');
    reopened.clear();
    expect(VendaCart(prefs).isEmpty, isTrue);
  });
}
