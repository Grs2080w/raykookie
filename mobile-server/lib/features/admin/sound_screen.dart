import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_common/ui/toast.dart';
import 'package:raykookie_common/ui/widgets.dart';

import '../../core/order_sounds.dart';
import '../../core/push.dart';

/// "Som de novos pedidos": escolhe e pré-ouve o som do aviso.
class SoundScreen extends StatefulWidget {
  const SoundScreen({super.key});
  @override
  State<SoundScreen> createState() => _SoundScreenState();
}

class _SoundScreenState extends State<SoundScreen> {
  final _player = AudioPlayer();

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  Future<void> _preview(OrderSound s) async {
    try {
      await _player.stop();
      await _player.play(AssetSource(s.asset));
    } catch (_) {
      Toast.error('Não foi possível tocar o som.');
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final push = context.watch<PushService>();
    final note = !push.available
        ? 'As notificações ainda não estão ativas nesta versão do app (falta configurar o Firebase). Você já pode escolher o som; ele vale assim que o push for ativado.'
        : !push.permissionGranted
            ? 'As notificações estão bloqueadas para este app. Ative em Configurações do celular → Apps → raykookie gestão → Notificações.'
            : null;
    return Scaffold(
      appBar: AppBar(title: const Text('Som de novos pedidos')),
      body: ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 32), children: [
        Text('Escolha o som do aviso quando chegar um pedido. Toque no play para ouvir.', style: TextStyle(color: c.muted)),
        const SizedBox(height: 14),
        if (note != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: AppCard(child: Text(note, style: TextStyle(color: c.muted, fontSize: 13))),
          ),
        RadioGroup<String>(
          groupValue: push.soundId,
          onChanged: (v) {
            if (v != null) push.setSound(v);
          },
          child: Column(children: [
            for (final s in orderSounds)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: AppCard(
                  onTap: () => push.setSound(s.id),
                  color: push.soundId == s.id ? c.pinkTint : null,
                  borderColor: push.soundId == s.id ? c.pink : null,
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                  child: Row(children: [
                    Radio<String>(value: s.id, activeColor: c.pink),
                    Expanded(child: Text(s.label, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
                    IconButton.filled(
                      tooltip: 'Ouvir ${s.label}',
                      icon: const Icon(Icons.play_arrow_rounded, size: 26),
                      style: IconButton.styleFrom(
                        minimumSize: const Size(48, 48),
                        backgroundColor: push.soundId == s.id ? c.pink : c.surface2,
                        foregroundColor: push.soundId == s.id ? c.onPink : c.pinkStrong,
                      ),
                      onPressed: () => _preview(s),
                    ),
                    const SizedBox(width: 6),
                  ]),
                ),
              ),
          ]),
        ),
      ]),
    );
  }
}
