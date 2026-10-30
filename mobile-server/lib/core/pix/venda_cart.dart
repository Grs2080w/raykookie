import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Uma linha do carrinho da Venda presencial. O preço fica guardado em centavos inteiros para o total do Pix não herdar
/// erro de ponto flutuante.
class CartLine {
  CartLine({required this.id, required this.name, required this.cents, required this.qty});
  final String id, name;
  final int cents;
  int qty;
  int get subtotalCents => cents * qty;
  Map<String, dynamic> toJson() => {'id': id, 'name': name, 'cents': cents, 'qty': qty};
  factory CartLine.fromJson(Map<String, dynamic> j) =>
      CartLine(id: '${j['id']}', name: '${j['name']}', cents: (j['cents'] as num).toInt(), qty: (j['qty'] as num).toInt());
}

/// Carrinho da Venda presencial (só no celular): vários produtos e quantidades num único Pix. Funciona sem internet e
/// fica guardado no aparelho, então volta igual se o app for minimizado ou fechado no meio da venda. Não mexe em estoque
/// nem manda a venda para o servidor: a confeiteira acerta isso à mão depois.
class VendaCart extends ChangeNotifier {
  VendaCart([this._prefs]) {
    _lines.addAll(_read());
  }

  static const _key = 'venda_cart';

  /// Quantidade máxima de cada item (a mesma da folha de quantidade da tela).
  static const maxQty = 99;
  final SharedPreferences? _prefs; // nulo = só na memória (testes)
  final List<CartLine> _lines = [];

  List<CartLine> get lines => List.unmodifiable(_lines);
  bool get isEmpty => _lines.isEmpty;

  /// Soma das quantidades (é o número do selo do botão do carrinho).
  int get itemCount => _lines.fold(0, (s, l) => s + l.qty);
  int get totalCents => _lines.fold(0, (s, l) => s + l.subtotalCents);
  double get total => totalCents / 100;

  List<CartLine> _read() {
    try {
      final raw = _prefs?.getString(_key);
      if (raw == null) return [];
      return [
        for (final e in jsonDecode(raw) as List) CartLine.fromJson(Map<String, dynamic>.from(e as Map)),
      ].where((l) => l.qty >= 1 && l.cents > 0).toList();
    } catch (_) {
      return [];
    }
  }

  void _changed() {
    _prefs?.setString(_key, jsonEncode([for (final l in _lines) l.toJson()]));
    notifyListeners();
  }

  /// Soma 1 unidade do produto. Produto sem preço não entra (devolve `false`); no limite de [maxQty] não passa.
  bool add(MenuItem item) {
    final cents = (item.price * 100).round();
    if (cents <= 0) return false;
    final found = _lines.where((l) => l.id == item.id).firstOrNull;
    if (found == null) {
      _lines.add(CartLine(id: item.id, name: item.name, cents: cents, qty: 1));
    } else if (found.qty < maxQty) {
      found.qty++;
    }
    _changed();
    return true;
  }

  /// Muda a quantidade (inteiro); 0 ou menos tira o item.
  void setQty(String id, int qty) {
    final line = _lines.where((l) => l.id == id).firstOrNull;
    if (line == null) return;
    if (qty <= 0) {
      _lines.remove(line);
    } else {
      line.qty = qty > maxQty ? maxQty : qty;
    }
    _changed();
  }

  void remove(String id) => setQty(id, 0);

  void clear() {
    if (_lines.isEmpty) return;
    _lines.clear();
    _changed();
  }

  /// Texto curto do carrinho para o histórico do Pix ("2× Bolo, 3× Brigadeiro"), cortado em [max] letras.
  String summary([int max = 110]) {
    final text = _lines.map((l) => '${l.qty}× ${l.name}').join(', ');
    return text.length <= max ? text : '${text.substring(0, max - 1)}…';
  }
}
