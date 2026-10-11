import 'dart:async';
import 'dart:convert';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:raykookie_common/core/app_updates.dart';
import 'package:raykookie_common/core/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Avisos do pedido no celular da cliente: pagamento confirmado, em preparo, pronto, entregue, cancelado pela loja e devolução.
///
/// A tela de pedidos só depende desta interface; a de verdade é [FirebaseCustomerPush] e os testes usam uma de mentira.
class CustomerPush extends ChangeNotifier {
  /// O Firebase está configurado neste build (google-services.json presente). Sem ele, nada de avisos e nada de cartão.
  bool available = false;

  /// A cliente já autorizou as notificações.
  bool granted = false;

  /// Tocou em "Agora não": o cartão não aparece de novo neste aparelho.
  bool dismissed = false;

  /// Mostra o cartão "Quer saber quando o pedido mudar?".
  bool get shouldOffer => available && !granted && !dismissed;

  /// Muda a cada aviso que chega com o app aberto: a tela de pedidos recarrega sozinha.
  int received = 0;

  /// Um aviso chegou com o app aberto.
  void bump() {
    received++;
    notifyListeners();
  }

  /// Chamado ao tocar num aviso, com a rota a abrir (Meus pedidos, no pedido certo).
  void Function(String route)? onOpen;

  /// Pede a autorização (Android 13+) e registra o celular no backend.
  Future<void> enable() async {}

  /// "Agora não".
  Future<void> dismiss() async {
    dismissed = true;
    notifyListeners();
  }

  /// Tira o celular da lista de avisos. Chamar ANTES de sair ou excluir a conta (precisa da sessão).
  Future<void> unregister() async {}

  /// Rota de Meus pedidos para um aviso (os dados vêm do backend: type e orderId).
  static String routeFor(Map<String, dynamic> data) {
    if (data['type'] == 'update') return '/'; // versão nova: a faixa de atualizar fica no início
    final id = data['orderId']?.toString();
    return id == null || id.isEmpty ? '/cliente/pedidos' : '/cliente/pedidos?pedido=$id';
  }
}

class FirebaseCustomerPush extends CustomerPush {
  FirebaseCustomerPush({required this.session, this.prefs});
  final Session session;
  final SharedPreferences? prefs;

  static const _kDismissed = 'push_offer_dismissed';
  static const channelId = 'pedidos_cliente'; // o mesmo que o backend manda em cada aviso

  final _local = FlutterLocalNotificationsPlugin();
  String? _token;
  bool _wasCustomer = false;

  Future<void> init() async {
    dismissed = prefs?.getBool(_kDismissed) ?? false;
    session.addListener(_onSession);
    if (kIsWeb) return; // o app da loja é Android; no navegador não há avisos
    try {
      await Firebase.initializeApp();
    } catch (e) {
      debugPrint('[avisos] Firebase indisponível (falta google-services.json?): $e');
      return;
    }
    available = true;
    await _local.initialize(
      settings: const InitializationSettings(android: AndroidInitializationSettings('@mipmap/ic_launcher')),
      onDidReceiveNotificationResponse: (r) {
        final data = _decode(r.payload);
        onUpdatePush(data);
        onOpen?.call(CustomerPush.routeFor(data));
      },
    );
    await _local
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(const AndroidNotificationChannel(
          channelId,
          'Seus pedidos',
          description: 'Pagamento confirmado, pedido pronto, cancelamentos e devoluções.',
          importance: Importance.high,
        ));
    await _local
        .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(const AndroidNotificationChannel(
          updatesChannelId,
          updatesChannelName,
          description: 'Aviso quando sai uma versão nova do app.',
        ));
    FirebaseMessaging.onMessage.listen(_showForeground);
    FirebaseMessaging.onMessageOpenedApp.listen((m) {
      bump(); // o app estava em segundo plano: a lista pode estar velha
      onUpdatePush(m.data);
      onOpen?.call(CustomerPush.routeFor(m.data));
    });
    FirebaseMessaging.instance.onTokenRefresh.listen((_) => unawaited(_register()));
    final launch = await FirebaseMessaging.instance.getInitialMessage(); // o app estava fechado e abriu pelo aviso
    if (launch != null) {
      onUpdatePush(launch.data);
      onOpen?.call(CustomerPush.routeFor(launch.data));
    }
    final s = await FirebaseMessaging.instance.getNotificationSettings();
    granted = s.authorizationStatus == AuthorizationStatus.authorized || s.authorizationStatus == AuthorizationStatus.provisional;
    _wasCustomer = session.isCustomer;
    if (_wasCustomer && granted) unawaited(_register());
    notifyListeners();
  }

  Map<String, dynamic> _decode(String? payload) {
    try {
      return payload == null ? const {} : Map<String, dynamic>.from(jsonDecode(payload) as Map);
    } catch (_) {
      return const {};
    }
  }

  void _onSession() {
    final c = session.isCustomer;
    if (c && !_wasCustomer && granted) unawaited(_register());
    _wasCustomer = c;
  }

  Future<void> _showForeground(RemoteMessage m) async {
    bump(); // a lista de pedidos se atualiza na hora
    onUpdatePush(m.data); // versão nova com o app aberto: a faixa aparece na hora, sem notificação
    if (m.data['type'] == 'update') return;
    final n = m.notification;
    if (n == null) return;
    await _local.show(
      id: m.hashCode,
      title: n.title,
      body: n.body,
      payload: jsonEncode(m.data),
      notificationDetails: const NotificationDetails(
        android: AndroidNotificationDetails(channelId, 'Seus pedidos', importance: Importance.high, priority: Priority.high),
      ),
    );
  }

  @override
  Future<void> enable() async {
    if (!available) return;
    try {
      final s = await FirebaseMessaging.instance.requestPermission();
      granted = s.authorizationStatus == AuthorizationStatus.authorized || s.authorizationStatus == AuthorizationStatus.provisional;
      if (!granted) {
        dismissed = true; // recusou no sistema: não insistimos (dá para ligar nas configurações do celular)
        await prefs?.setBool(_kDismissed, true);
      }
      notifyListeners();
      if (granted) await _register();
    } catch (e) {
      debugPrint('[avisos] não pediu a autorização: $e');
    }
  }

  @override
  Future<void> dismiss() async {
    await prefs?.setBool(_kDismissed, true);
    await super.dismiss();
  }

  Future<void> _register() async {
    if (!available || !session.isCustomer) return;
    try {
      _token = await FirebaseMessaging.instance.getToken();
      if (_token == null) return;
      await session.customerApi.post('/api/cliente/devices', {'token': _token});
    } catch (e) {
      debugPrint('[avisos] não registrou o aparelho: $e'); // sem internet: tenta de novo na próxima abertura
    }
  }

  @override
  Future<void> unregister() async {
    final t = _token ?? (available ? await FirebaseMessaging.instance.getToken().catchError((_) => null) : null);
    if (t == null) return;
    try {
      await session.customerApi.delete('/api/cliente/devices?token=${Uri.encodeQueryComponent(t)}');
    } catch (_) {
      // sai mesmo sem internet; o Firebase descarta o token morto depois
    }
  }

  @override
  void dispose() {
    session.removeListener(_onSession);
    super.dispose();
  }
}
