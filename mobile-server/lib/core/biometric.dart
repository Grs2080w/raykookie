import 'package:flutter/foundation.dart';
import 'package:local_auth/local_auth.dart';
import 'package:raykookie_common/core/session.dart';

/// Acesso à biometria do aparelho (digital ou rosto). Separado para os testes usarem um de mentira.
abstract class BiometricAuth {
  /// Há digital/rosto cadastrado neste aparelho?
  Future<bool> available();

  /// Pede a biometria; aceita também o PIN/padrão do aparelho como reserva (para a pessoa nunca ficar trancada de fora).
  Future<bool> authenticate(String reason);
}

class LocalBiometricAuth implements BiometricAuth {
  final _auth = LocalAuthentication();

  @override
  Future<bool> available() async {
    try {
      return await _auth.canCheckBiometrics &&
          (await _auth.getAvailableBiometrics()).isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<bool> authenticate(String reason) async {
    try {
      return await _auth.authenticate(localizedReason: reason);
    } on LocalAuthException {
      return false; // cancelou, errou demais ou o aparelho bloqueou: continua trancado
    } catch (_) {
      return false;
    }
  }
}

/// Biometria ligada à conta da confeiteira: pergunta uma vez se quer ativar e, ativada, tranca o app ao abrir
/// (e ao voltar depois de um tempo em segundo plano). É uma trava de tela do app: a sessão continua no cofre do aparelho.
class BiometricLock extends ChangeNotifier {
  BiometricLock({
    required this.session,
    required this.auth,
    this.relockAfter = const Duration(seconds: 30),
  }) {
    session.addListener(_onSession);
  }

  final Session session;
  final BiometricAuth auth;
  final Duration relockAfter;

  static const _kLast = 'bio_last_user';
  String _on(String u) => 'bio_on:$u';
  String _asked(String u) => 'bio_asked:$u';

  bool enabled = false;
  bool locked = false;
  bool canUse = false; // o aparelho tem biometria cadastrada
  bool askToEnable = false; // mostrar "Quer entrar com biometria?"
  bool _busy = false;
  String _user = '';
  DateTime? _pausedAt;

  TokenStore get _store => session.store;

  /// Chamar uma vez na abertura do app, antes de desenhar a primeira tela (evita piscar o painel destrancado).
  Future<void> init() async {
    await _load();
    locked = enabled && session.isOwner;
    notifyListeners();
  }

  Future<void> _load() async {
    if (!session.isOwner) {
      enabled = false;
      askToEnable = false;
      return;
    }
    // sem internet o nome da conta pode não ter carregado: usa o último que entrou neste aparelho
    _user = session.ownerName ?? await _store.read(_kLast) ?? '';
    if (session.ownerName != null)
      await _store.write(_kLast, session.ownerName!);
    enabled = await _store.read(_on(_user)) == '1';
    canUse = await auth.available();
    askToEnable = canUse && !enabled && await _store.read(_asked(_user)) != '1';
  }

  void _onSession() {
    if (!session.isOwner) {
      enabled = locked = askToEnable = false;
      notifyListeners();
    } else {
      _load().then(
        (_) => notifyListeners(),
      ); // entrou agora (digitou a senha): não tranca, só pode perguntar
    }
  }

  /// Destranca com a biometria. Devolve se conseguiu.
  Future<bool> unlock() async {
    if (_busy) return false;
    _busy = true;
    try {
      final ok = await auth.authenticate('Entre no raykookie business');
      if (ok) {
        locked = false;
        notifyListeners();
      }
      return ok;
    } finally {
      _busy = false;
    }
  }

  /// "Ativar": confirma com a biometria antes de ligar (garante que funciona neste aparelho).
  Future<bool> enable() async {
    if (!await auth.authenticate('Confirme para ativar a biometria'))
      return false;
    await _store.write(_on(_user), '1');
    await _store.write(_asked(_user), '1');
    enabled = true;
    askToEnable = false;
    notifyListeners();
    return true;
  }

  Future<bool> disable() async {
    if (!await auth.authenticate('Confirme para desativar a biometria'))
      return false;
    await _store.delete(_on(_user));
    enabled = false;
    notifyListeners();
    return true;
  }

  /// "Agora não": não pergunta de novo (dá para ligar depois em Mais).
  Future<void> dismissPrompt() async {
    await _store.write(_asked(_user), '1');
    askToEnable = false;
    notifyListeners();
  }

  void onPaused() => _pausedAt = DateTime.now();

  void onResumed() {
    final at = _pausedAt;
    _pausedAt = null;
    if (enabled &&
        session.isOwner &&
        !locked &&
        at != null &&
        DateTime.now().difference(at) >= relockAfter) {
      locked = true;
      notifyListeners();
    }
  }

  Future<void> logoutFromLock() async {
    await session.logoutOwner();
    locked = false;
    notifyListeners();
  }

  @override
  void dispose() {
    session.removeListener(_onSession);
    super.dispose();
  }
}
