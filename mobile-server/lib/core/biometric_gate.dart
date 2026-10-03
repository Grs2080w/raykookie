import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import 'biometric.dart';

/// Fica por cima do app: tela de bloqueio quando a biometria está ativada e o aviso "Quer ativar?" na primeira vez.
class BiometricGate extends StatefulWidget {
  const BiometricGate({super.key, required this.child});
  final Widget child;
  @override
  State<BiometricGate> createState() => _BiometricGateState();
}

class _BiometricGateState extends State<BiometricGate>
    with WidgetsBindingObserver {
  bool _autoTried = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final lock = context.read<BiometricLock>();
    if (state == AppLifecycleState.paused) lock.onPaused();
    if (state == AppLifecycleState.resumed) {
      lock.onResumed();
      _autoTried = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final lock = context.watch<BiometricLock>();
    if (lock.locked && !_autoTried) {
      _autoTried = true;
      WidgetsBinding.instance.addPostFrameCallback(
        (_) => lock.unlock(),
      ); // já pede a digital ao abrir
    }
    if (!lock.locked) _autoTried = false;
    return Stack(
      children: [
        Positioned.fill(child: widget.child),
        if (lock.locked) const Positioned.fill(child: _LockScreen()),
        if (!lock.locked && lock.askToEnable)
          const Positioned.fill(child: _EnableSheet()),
      ],
    );
  }
}

class _LockScreen extends StatelessWidget {
  const _LockScreen();
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final lock = context.read<BiometricLock>();
    return Material(
      color: c.bg,
      child: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const RkLogo(variant: 'simbolo', height: 64),
                const SizedBox(height: 22),
                Text('Raykookie gestão', style: display(context, size: 30)),
                const SizedBox(height: 6),
                Text(
                  'Use sua biometria para entrar.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: c.muted),
                ),
                const SizedBox(height: 26),
                BusyButton(
                  label: 'Desbloquear',
                  icon: Icons.fingerprint_rounded,
                  onPressed: () async {
                    if (!await lock.unlock())
                      Toast.error('Não deu para confirmar. Tente de novo.');
                  },
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: lock.logoutFromLock,
                  child: const Text('Sair da conta'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _EnableSheet extends StatefulWidget {
  const _EnableSheet();
  @override
  State<_EnableSheet> createState() => _EnableSheetState();
}

class _EnableSheetState extends State<_EnableSheet> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final lock = context.read<BiometricLock>();
    return Material(
      color: Colors.black54,
      child: SafeArea(
        child: Align(
          alignment: Alignment.bottomCenter,
          child: Container(
            margin: const EdgeInsets.all(16),
            padding: const EdgeInsets.fromLTRB(22, 22, 22, 18),
            decoration: BoxDecoration(
              color: c.surface,
              borderRadius: BorderRadius.circular(28),
              border: Border.all(color: c.line),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 52,
                  height: 52,
                  decoration: BoxDecoration(
                    color: c.pinkTint,
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Icon(
                    Icons.fingerprint_rounded,
                    color: c.pinkStrong,
                    size: 30,
                  ),
                ),
                const SizedBox(height: 14),
                Text(
                  'Entrar com biometria?',
                  style: display(context, size: 26),
                ),
                const SizedBox(height: 6),
                Text(
                  'Da próxima vez, abra o app com a digital ou o rosto, sem digitar a senha. Seus dados ficam protegidos se alguém pegar seu celular. Você pode mudar isso depois em Mais.',
                  style: TextStyle(color: c.muted, height: 1.4),
                ),
                const SizedBox(height: 18),
                BusyButton(
                  label: 'Ativar biometria',
                  icon: Icons.lock_outline_rounded,
                  busy: _busy,
                  onPressed: () async {
                    setState(() => _busy = true);
                    final ok = await lock.enable();
                    if (!mounted) return;
                    setState(() => _busy = false);
                    ok
                        ? Toast.ok('Biometria ativada')
                        : Toast.error(
                            'Não foi possível confirmar a biometria. Você pode tentar de novo em Mais.',
                          );
                  },
                ),
                const SizedBox(height: 6),
                Center(
                  child: TextButton(
                    onPressed: _busy ? null : lock.dismissPrompt,
                    child: const Text('Agora não'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
