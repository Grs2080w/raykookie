import 'package:flutter/material.dart' show ThemeMode, Brightness;
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';
import 'models.dart';

abstract class TokenStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Guarda o token no cofre do sistema (Keystore no Android, Keychain no iOS).
class SecureTokenStore implements TokenStore {
  const SecureTokenStore([this._s = const FlutterSecureStorage()]);
  final FlutterSecureStorage _s;
  @override
  Future<String?> read(String key) => _s.read(key: key);
  @override
  Future<void> write(String key, String value) => _s.write(key: key, value: value);
  @override
  Future<void> delete(String key) => _s.delete(key: key);
}

class MemoryTokenStore implements TokenStore {
  final _m = <String, String>{};
  @override
  Future<String?> read(String key) async => _m[key];
  @override
  Future<void> write(String key, String value) async => _m[key] = value;
  @override
  Future<void> delete(String key) async => _m.remove(key);
}

/// Quem está logado: a confeiteira (painel) e/ou o cliente (loja). Cada um tem o seu token.
class Session extends ChangeNotifier {
  Session({required this.store, ApiClient? owner, ApiClient? customer})
      : ownerApi = owner ?? ApiClient(),
        customerApi = customer ?? ApiClient() {
    ownerApi.onUnauthorized = () => _expire(owner: true);
    customerApi.onUnauthorized = () => _expire(owner: false);
  }

  static const _kOwner = 'owner_token', _kCustomer = 'customer_token';
  final TokenStore store;
  final ApiClient ownerApi, customerApi;

  bool ready = false;
  String? ownerName;
  CustomerProfile? profile;

  bool get isOwner => ownerApi.token != null;
  bool get isCustomer => customerApi.token != null;

  /// Lê os tokens salvos e confere com o servidor. Sem internet, mantém a sessão como está.
  Future<void> load() async {
    ownerApi.token = await store.read(_kOwner);
    customerApi.token = await store.read(_kCustomer);
    if (isOwner) {
      try {
        ownerName = (await ownerApi.get('/api/me'))['username'] as String?;
      } on ApiException catch (e) {
        if (e.unauthorized) await _clear(owner: true);
      }
    }
    if (isCustomer) await refreshProfile();
    ready = true;
    notifyListeners();
  }

  /// Só lê os tokens salvos, sem falar com o servidor: abertura instantânea e que funciona sem internet.
  /// Depois, [load] confere a sessão com o servidor em segundo plano.
  Future<void> loadLocal() async {
    ownerApi.token = await store.read(_kOwner);
    customerApi.token = await store.read(_kCustomer);
    notifyListeners();
  }

  Future<void> refreshProfile() async {
    try {
      profile = CustomerProfile.fromJson(await customerApi.get('/api/cliente/me'));
    } on ApiException catch (e) {
      if (e.unauthorized) await _clear(owner: false);
    }
    notifyListeners();
  }

  // ---- confeiteira ------------------------------------------------------------------------------
  Future<void> loginOwner(String username, String password) => _ownerAuth('/api/auth/login', username, {'username': username, 'password': password});
  Future<void> registerOwner(String username, String password) => _ownerAuth('/api/auth/register', username, {'username': username, 'password': password});

  Future<void> _ownerAuth(String path, String username, Map<String, String> body) async {
    final r = await ownerApi.post(path, body);
    final token = r is Map ? r['token'] as String? : null;
    if (token == null) throw ApiException(500, 'Não foi possível entrar agora. Tente de novo.');
    ownerApi.token = token;
    await store.write(_kOwner, token);
    ownerName = username;
    notifyListeners();
  }

  Future<void> logoutOwner() async {
    try {
      await ownerApi.post('/api/auth/logout');
    } catch (_) {/* sair localmente mesmo sem internet */}
    await _clear(owner: true);
  }

  // ---- cliente ----------------------------------------------------------------------------------
  Future<void> loginCustomer(String email, String password) => _customerAuth('/api/cliente/login', {'email': email, 'password': password});
  Future<void> registerCustomer(String name, String email, String password) =>
      _customerAuth('/api/cliente/cadastro', {'name': name, 'email': email, 'password': password});
  Future<void> loginWithGoogle(String idToken) => _customerAuth('/api/cliente/google/token', {'idToken': idToken});

  Future<void> _customerAuth(String path, Map<String, String> body) async {
    final r = await customerApi.post(path, body);
    final token = r is Map ? r['token'] as String? : null;
    if (token == null) throw ApiException(500, 'Não foi possível entrar agora. Tente de novo.');
    customerApi.token = token;
    await store.write(_kCustomer, token);
    await refreshProfile();
  }

  Future<void> logoutCustomer() async {
    try {
      await customerApi.post('/api/cliente/logout');
    } catch (_) {}
    await _clear(owner: false);
  }

  Future<void> _expire({required bool owner}) => _clear(owner: owner);

  Future<void> _clear({required bool owner}) async {
    if (owner) {
      ownerApi.token = null;
      ownerName = null;
      await store.delete(_kOwner);
    } else {
      customerApi.token = null;
      profile = null;
      await store.delete(_kCustomer);
    }
    notifyListeners();
  }
}

/// Tema do painel (claro, escuro ou do sistema), lembrado entre aberturas do app.
class ThemeController extends ChangeNotifier {
  ThemeController([this._prefs]);
  final SharedPreferences? _prefs;
  ThemeMode mode = ThemeMode.system;

  Future<void> load() async {
    final v = _prefs?.getString('theme');
    mode = v == 'dark' ? ThemeMode.dark : v == 'light' ? ThemeMode.light : ThemeMode.system;
    notifyListeners();
  }

  Future<void> toggle(Brightness current) async {
    mode = current == Brightness.dark ? ThemeMode.light : ThemeMode.dark;
    await _prefs?.setString('theme', mode == ThemeMode.dark ? 'dark' : 'light');
    notifyListeners();
  }
}
