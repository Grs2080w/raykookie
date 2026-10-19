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

  static const _kPix = 'venda_pix', _kMenu = 'venda_menu', _kEvents = 'venda_events';
  static const _maxQueued = 200, _batch = 50;
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
    await report('chave_alterada', keyKind: c.kind);
    notifyListeners();
  }

  Future<void> clearConfig() async {
    final removed = config;
    await _prefs?.remove(_kPix);
    config = null;
    if (removed != null) await report('chave_removida', keyKind: removed.kind);
    notifyListeners();
  }

  /// Eventos esperando internet para irem ao histórico do servidor (QR gerado, chave alterada...).
  List<Map<String, dynamic>> get pendingEvents {
    try {
      final raw = _prefs?.getString(_kEvents);
      return raw == null ? [] : [for (final e in jsonDecode(raw) as List) Map<String, dynamic>.from(e as Map)];
    } catch (_) {
      return [];
    }
  }

  /// Guarda no aparelho um evento da venda presencial para o histórico. Nunca leva a chave Pix, só o tipo dela.
  /// [kind]: `qr_gerado`, `chave_alterada` ou `chave_removida`.
  Future<void> report(String kind, {String? title, double? amount, PixKeyKind? keyKind}) async {
    final queue = pendingEvents..add({
      'kind': kind,
      if (title != null) 'title': title,
      if (amount != null) 'amount': amount,
      if (keyKind != null) 'key_kind': keyKind.label,
      'at': DateTime.now().toIso8601String(),
    });
    if (queue.length > _maxQueued) queue.removeRange(0, queue.length - _maxQueued);
    await _prefs?.setString(_kEvents, jsonEncode(queue));
  }

  bool _flushing = false;

  /// Manda a fila ao servidor em lotes. Sem internet (ou com erro) a fila fica como está para a próxima vez.
  Future<void> flushEvents(ApiClient api) async {
    if (_flushing) return;
    _flushing = true;
    try {
      while (true) {
        final queue = pendingEvents;
        if (queue.isEmpty) return;
        final batch = queue.take(_batch).toList();
        await api.post('/api/historico/pix-presencial', {'events': batch});
        await _prefs?.setString(_kEvents, jsonEncode(queue.skip(batch.length).toList()));
      }
    } catch (_) {
      // offline, sessão vencida ou erro do servidor: tenta de novo na próxima abertura
    } finally {
      _flushing = false;
    }
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
