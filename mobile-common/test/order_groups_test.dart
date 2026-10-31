import 'package:flutter_test/flutter_test.dart';
import 'package:raykookie_common/core/models.dart';

OrderModel _line(int id, {String? group, double qty = 1, double price = 10}) => OrderModel.fromJson({
      'id': id, 'number': id, 'order_date': '2026-10-01', 'customer': 'Ana', 'product': 'P$id', 'qty': qty,
      'unit_price': price, 'status': 'novo', 'payment': 'pendente', 'notes': '', 'group_id': group,
    });

void main() {
  test('linhas do mesmo grupo viram um pedido só, na posição da primeira', () {
    final rows = groupOrders([_line(5, group: 'g'), _line(4), _line(3, group: 'g')]);
    expect(rows.length, 2);
    expect(rows[0].map((o) => o.id), [3, 5]);
    expect(rows[1].map((o) => o.id), [4]);
  });

  test('o total do pedido soma os itens e o pedido com vários itens não gera link Pix', () {
    final rows = groupOrders([_line(1, group: 'g', qty: 2, price: 50), _line(2, group: 'g', qty: 10, price: 3.5)]);
    expect(rows.single.fold<double>(0, (s, o) => s + o.total), 135);
    expect(rows.single.first.canPayLink, isFalse);
    expect(_line(9).canPayLink, isTrue);
  });
}
