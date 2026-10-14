import 'dart:async';
import 'dart:math';

import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';
import 'engagement_catalog.dart';

/// Medição anônima do app do cliente (plano: docs/superpowers/plans/2026-10-13-engajamento-clientes.md). O único
/// código é um UUID aleatório da visita, que vive na memória e muda depois de 30 minutos parado ou ao reabrir o app:
/// sem conta, sem aparelho, sem texto digitado. Fica desligada, a menos que o build use
/// `--dart-define=ENGAGEMENT_ENABLED=true`, e também para quem escolheu "não medir".
class Analytics {
  Analytics({DateTime Function()? now, Future<void> Function(Map<String, Object?> body)? send}) : _now = now ?? DateTime.now {
    _send = send;
  }

  /// A instância do app (configurada em `main`).
  static final Analytics instance = Analytics();

  /// O build liga a medição.
  static const enabledAtBuild = bool.fromEnvironment('ENGAGEMENT_ENABLED');

  static const _optOutKey = 'rk_no_track';
  static const _maxBatch = 20;
  static const _maxQueue = 200;
  static const _idle = Duration(minutes: 30);
  static const _flushEvery = Duration(seconds: 5);
  static const _repeatGap = Duration(seconds: 3);

  final DateTime Function() _now;
  Future<void> Function(Map<String, Object?> body)? _send;
  SharedPreferences? _prefs;
  String _platform = 'android';
  String _appVersion = '';
  bool _enabled = enabledAtBuild;
  bool _optedOut = false;
  Timer? _timer;

  final List<Map<String, Object?>> _queue = [];
  final Set<String> _once = {};
  final Map<String, DateTime> _seen = {};
  String? _sessionId;
  DateTime? _lastActivity;

  /// Liga a medição neste app. `send` padrão: POST em `/api/medicao/eventos` (sem sessão do cliente).
  void configure({required ApiClient api, required SharedPreferences prefs, required String platform, String appVersion = '', bool? enabled}) {
    _prefs = prefs;
    _platform = platform;
    _appVersion = appVersion;
    _enabled = enabled ?? enabledAtBuild;
    _optedOut = prefs.getBool(_optOutKey) ?? false;
    _send ??= (body) => api.post('/api/medicao/eventos', body);
    WidgetsBinding.instance.addObserver(_Lifecycle(this));
  }

  /// A medição está ligada no build (a tela "Meus dados" só mostra o interruptor se estiver).
  bool get configured => _enabled;

  /// A pessoa escolheu não ser medida.
  bool get optedOut => _optedOut;

  /// Guarda a escolha. Ao recusar, o que estava na fila é descartado.
  Future<void> setOptOut(bool value) async {
    _optedOut = value;
    if (value) _queue.clear();
    await _prefs?.setBool(_optOutKey, value);
  }

  /// Registra um evento do catálogo. Vista e clique de um item contam uma vez por visita, e o mesmo evento repetido
  /// em menos de 3 s é ignorado (o servidor garante o mesmo, então nada duplica).
  void track(String name, {String? route, String? itemId, String? detail, int? qty}) {
    if (!_enabled || _optedOut) return;
    final rule = engagementEvents[name];
    if (rule == null) return;
    if ((rule.route && (route == null || route.isEmpty)) || (rule.item && (itemId == null || itemId.isEmpty)) || (rule.qty && (qty == null || qty < 1 || qty > 99))) return;
    if (detail != null && detail.isNotEmpty && !rule.details.contains(detail)) return;
    if (rule.detailRequired && (detail == null || detail.isEmpty)) return;

    final now = _now();
    _touchSession(now);
    if (name == 'menu_item_view' || name == 'menu_item_click') {
      if (!_once.add('$name|$itemId')) return;
    }
    final key = '$name|${route ?? ''}|${itemId ?? ''}|${detail ?? ''}';
    final last = _seen[key];
    if (name != 'cart_add' && name != 'cart_remove' && last != null && now.difference(last) < _repeatGap) return;
    _seen[key] = now;

    _queue.add({
      'name': name,
      'at': now.millisecondsSinceEpoch,
      if (rule.route) 'route': route,
      if (rule.item) 'item_id': itemId,
      if (detail != null && detail.isNotEmpty) 'detail': detail,
      if (rule.qty) 'qty': qty,
    });
    if (_queue.length >= _maxBatch) {
      unawaited(flush());
    } else {
      _timer ??= Timer(_flushEvery, () => unawaited(flush()));
    }
  }

  /// Uma tela vista (`location` = caminho do `go_router`, com ou sem consulta).
  void screen(String location) => track('screen_view', route: routeOf(location));

  /// Caminho sem consulta e com ids numéricos trocados por `:id`.
  static String routeOf(String location) {
    final path = location.split(RegExp('[?#]')).first;
    return path.replaceAllMapped(RegExp(r'/[0-9]+(?=/|$)'), (_) => '/:id');
  }

  /// Envia o que está na fila. Falha de rede devolve os eventos à fila (até 200); lote recusado (4xx) é descartado.
  Future<void> flush() async {
    _timer?.cancel();
    _timer = null;
    final send = _send;
    if (send == null) return;
    while (_queue.isNotEmpty) {
      final take = _queue.length < _maxBatch ? _queue.length : _maxBatch;
      final events = _queue.sublist(0, take);
      _queue.removeRange(0, take);
      try {
        await send({'session_id': _sessionId, 'platform': _platform, 'app_version': _appVersion, 'events': events});
      } on ApiException catch (e) {
        if (e.status == 0 || e.status >= 500 || e.status == 429) _requeue(events);
        return;
      } catch (_) {
        _requeue(events);
        return;
      }
    }
  }

  void _requeue(List<Map<String, Object?>> events) {
    _queue.insertAll(0, events);
    if (_queue.length > _maxQueue) _queue.removeRange(0, _queue.length - _maxQueue);
  }

  void _touchSession(DateTime now) {
    final last = _lastActivity;
    if (_sessionId == null || last == null || now.difference(last) >= _idle) {
      _sessionId = _uuid();
      _once.clear();
      _seen.clear();
    }
    _lastActivity = now;
  }

  static String _uuid() {
    final r = Random.secure();
    final b = List<int>.generate(16, (_) => r.nextInt(256));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
    return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
  }

  /// Só para teste: quantos eventos esperam envio.
  @visibleForTesting
  int get pending => _queue.length;
}

/// Manda o que está na fila quando o app vai para o fundo.
class _Lifecycle with WidgetsBindingObserver {
  _Lifecycle(this._analytics);
  final Analytics _analytics;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused || state == AppLifecycleState.inactive || state == AppLifecycleState.detached) {
      unawaited(_analytics.flush());
    }
  }
}
