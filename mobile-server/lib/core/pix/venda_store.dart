import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:raykookie_common/core/api_client.dart';
import 'package:raykookie_common/core/models.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'br_code.dart';

/// Rota da Venda Presencial: o widget da tela inicial abre o app direto aqui.
const vendaRoute = '/painel/venda-presencial';

/// Tudo o que a Venda Presencial precisa para funcionar sem internet, guardado no aparelho:
/// a chave Pix fixa e uma cópia do cardápio. A tela só lê daqui; a internet só serve para renovar a cópia.
class VendaStore extends ChangeNotifier {
  VendaStore([this._prefs]) {
    config = _readConfig();
    menu = _readMenu();
  }

  static const _kPix = 'venda_pix', _kMenu = 'venda_menu';
  final SharedPreferences? _prefs; // nulo = só na memória (testes)

  PixConfig? config;
  Menu menu = Menu.empty();
  bool refreshing = false;

  /// Produtos e combos, na ordem do cardápio.
  List<MenuItem> get items => menu.items;

  PixConfig? _readConfig() {
    try {
      final raw = _prefs?.getString(_kPix);
      return raw == null ? null : PixConfig.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (_) {
      return null;
    }
  }

  Menu _readMenu() {
    try {
      final raw = _prefs?.getString(_kMenu);
      return raw == null ? Menu.empty() : Menu.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (_) {
      return Menu.empty();
    }
  }

  Future<void> saveConfig(PixConfig c) async {
    await _prefs?.setString(_kPix, jsonEncode(c.toJson()));
    config = c;
    notifyListeners();
  }

  Future<void> clearConfig() async {
    await _prefs?.remove(_kPix);
    config = null;
    notifyListeners();
  }

  /// Renova a cópia do cardápio. Sem internet (ou com erro) mantém a cópia atual e não incomoda ninguém.
  Future<void> refreshMenu(ApiClient api) async {
    if (refreshing) return;
    refreshing = true;
    try {
      final r = await api.get('/api/menu');
      final fresh = Menu.fromJson(Map<String, dynamic>.from(r as Map));
      await _prefs?.setString(_kMenu, jsonEncode(r));
      menu = fresh;
      notifyListeners();
    } catch (_) {
      // offline: segue com o que está salvo
    } finally {
      refreshing = false;
    }
  }
}
