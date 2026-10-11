import 'dart:async';
import 'dart:convert';

import 'package:firebase_core/firebase_core.dart';
import 'package:raykookie_common/core/app_updates.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'order_sounds.dart';

/// Aviso de pedido novo (push do Firebase), mesmo com o app fechado.
///
/// - O backend manda o push já com o canal do som escolhido; o Android toca o som do canal.
/// - Com o app aberto o Firebase não mostra nada sozinho, então mostramos a notificação local no mesmo canal.
/// - Sem o google-services.json (credenciais do Firebase) o app funciona normalmente: [available] fica falso.
class PushService extends ChangeNotifier {
  PushService({required this.session, this.prefs});
  final Session session;
  final SharedPreferences? prefs; // nulo nos testes: o som fica só em memória
  String? _soundMem;

  static const _kSound = 'order_sound';
  static const ordersRoute = '/painel/pedidos';

  /// Muda a cada aviso que chega com o app aberto: Pedidos e Devoluções recarregam sozinhos.
  int received = 0;

  /// Firebase inicializado (credenciais presentes neste build).
  bool available = false;
  bool permissionGranted = true;

  /// Tela que cada aviso abre: devolução abre o detalhe dela, privacidade as solicitações, preço o início e o resto Pedidos.
  static String routeFor(Map<String, dynamic> data) {
    final type = data['type']?.toString();
    final refund = data['refundId']?.toString();
    if (type == 'devolucao' && refund != null && refund.isNotEmpty) return '/painel/devolucoes/$refund';
    if (type == 'privacidade') return '/painel/privacidade';
    if (type == 'preco' || type == 'update') return '/painel/inicio'; // versão nova: a faixa fica no início
    return ordersRoute;
  }

  Map<String, dynamic> _decode(String? payload) {
    try {
      return payload == null ? const {} : Map<String, dynamic>.from(jsonDecode(payload) as Map);
    } catch (_) {
      return const {};
    }
  }

  /// Chamado ao tocar numa notificação, com a rota da tela a abrir.
  void Function(String route)? onOpen;

  final _local = FlutterLocalNotificationsPlugin();
  String? _token;
  bool _wasOwner = false;

  OrderSound get sound =>
      orderSoundById(prefs?.getString(_kSound) ?? _soundMem);
  String get soundId => sound.id;

  Future<void> init() async {
    session.addListener(_onSession);
    try {
      await Firebase.initializeApp();
    } catch (e) {
      debugPrint(
        '[push] Firebase indisponível (falta google-services.json?): $e',
      );
      return;
    }
    available = true;
    await _local.initialize(
      settings: const InitializationSettings(
        android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      ),
      onDidReceiveNotificationResponse: (r) {
        final data = _decode(r.payload);
        onUpdatePush(data);
        onOpen?.call(routeFor(data));
      },
    );
    final android = _local
        .resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin
        >();
    // Um canal por som: no Android 8+ o som é do canal e não muda depois de criado.
    for (final s in orderSounds) {
      await android?.createNotificationChannel(
        AndroidNotificationChannel(
          s.channelId,
          'Novos pedidos · ${s.label}',
          description: 'Aviso de pedido novo com o som "${s.label}".',
          importance: Importance.max,
          playSound: true,
          sound: RawResourceAndroidNotificationSound(s.channelId),
        ),
      );
    }
    await android?.createNotificationChannel(
      const AndroidNotificationChannel(
        updatesChannelId,
        updatesChannelName,
        description: 'Aviso quando sai uma versão nova do app.',
      ),
    );
    FirebaseMessaging.onMessage.listen(_showForeground);
    FirebaseMessaging.onMessageOpenedApp.listen((m) {
      received++; // o app estava em segundo plano: Pedidos pode estar velho
      notifyListeners();
      onUpdatePush(m.data);
      onOpen?.call(routeFor(m.data));
    });
    FirebaseMessaging.instance.onTokenRefresh.listen((_) => _sync());
    final launch = await FirebaseMessaging.instance
        .getInitialMessage(); // app estava fechado e abriu pela notificação
    if (launch != null) {
      onUpdatePush(launch.data);
      onOpen?.call(routeFor(launch.data));
    }
    _wasOwner = session.isOwner;
    if (_wasOwner) unawaited(_sync());
  }

  void _onSession() {
    final owner = session.isOwner;
    if (owner && !_wasOwner) unawaited(_sync());
    _wasOwner = owner;
  }

  Future<void> _showForeground(RemoteMessage m) async {
    received++;
    notifyListeners();
    onUpdatePush(m.data); // versão nova com o app aberto: a faixa aparece na hora, sem notificação
    if (m.data['type'] == 'update') return;
    final n = m.notification;
    if (n == null) return;
    final s = orderSoundById(
      m.notification?.android?.channelId?.replaceFirst('pedido_', ''),
    );
    await _local.show(
      id: m.hashCode,
      title: n.title,
      body: n.body,
      payload: jsonEncode(m.data),
      notificationDetails: NotificationDetails(
        android: AndroidNotificationDetails(
          s.channelId,
          'Novos pedidos · ${s.label}',
          importance: Importance.max,
          priority: Priority.high,
          playSound: true,
          sound: RawResourceAndroidNotificationSound(s.channelId),
        ),
      ),
    );
  }

  /// Pede a autorização de notificações (Android 13+) e registra este celular no backend.
  Future<void> _sync() async {
    if (!available || !session.isOwner) return;
    try {
      final settings = await FirebaseMessaging.instance.requestPermission();
      permissionGranted =
          settings.authorizationStatus != AuthorizationStatus.denied;
      notifyListeners();
      _token = await FirebaseMessaging.instance.getToken();
      if (_token == null) return;
      await session.ownerApi.post('/api/devices', {
        'token': _token,
        'sound': soundId,
      });
    } catch (e) {
      debugPrint(
        '[push] não registrou o aparelho: $e',
      ); // sem internet etc.: tenta de novo na próxima abertura
    }
  }

  /// Tira este celular da lista de avisos. Chamar ANTES de sair da conta (precisa do token de sessão).
  Future<void> unregister() async {
    final t =
        _token ??
        (available
            ? await FirebaseMessaging.instance.getToken().catchError(
                (_) => null,
              )
            : null);
    if (t == null) return;
    try {
      await session.ownerApi.delete(
        '/api/devices?token=${Uri.encodeQueryComponent(t)}',
      );
    } catch (_) {
      /* sair mesmo sem internet; o Firebase descarta o token morto depois */
    }
  }

  /// Troca o som dos próximos pedidos (vale também com o app fechado, porque o backend escolhe o canal).
  Future<void> setSound(String id) async {
    _soundMem = orderSoundById(id).id;
    await prefs?.setString(_kSound, _soundMem!);
    notifyListeners();
    if (_token != null && session.isOwner) {
      try {
        await session.ownerApi.post('/api/devices', {
          'token': _token,
          'sound': soundId,
        });
      } catch (_) {
        /* o app reenvia o som na próxima abertura */
      }
    }
  }

  @override
  void dispose() {
    session.removeListener(_onSession);
    super.dispose();
  }
}
