import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:raykookie_common/ui/theme.dart';
import 'package:raykookie_gestao/core/order_sounds.dart';
import 'package:raykookie_gestao/core/push.dart';
import 'package:raykookie_gestao/features/admin/sound_screen.dart';

import 'helpers.dart';

void main() {
  test('catálogo: ids únicos, canal e arquivo derivados do id, desconhecido cai no padrão', () {
    expect(orderSounds.map((s) => s.id).toSet().length, orderSounds.length);
    expect(orderSounds.map((s) => s.id), containsAll(['sino', 'caixa', 'campainha', 'suave', 'alerta']));
    expect(orderSoundById('caixa').channelId, 'pedido_caixa');
    expect(orderSoundById('caixa').asset, 'sounds/pedido_caixa.wav');
    expect(orderSoundById('nada').id, defaultOrderSoundId);
    expect(orderSoundById(null).id, defaultOrderSoundId);
  });

  test('trocar o som lembra a escolha e, logado e com aparelho registrado, avisa o backend', () async {
    final api = FakeBackend({});
    final push = PushService(session: api.session());
    expect(push.soundId, 'sino');
    await push.setSound('alerta');
    expect(push.soundId, 'alerta');
    await push.setSound('inexistente');
    expect(push.soundId, defaultOrderSoundId);
    expect(api.calls, isEmpty); // sem aparelho registrado (sem Firebase) não chama o servidor
  });

  testWidgets('tela de som: lista os sons, avisa que o Firebase falta e escolher muda o som', (t) async {
    await loadAppFonts();
    final push = PushService(session: FakeBackend({}).session());
    await t.pumpWidget(MultiProvider(
      providers: [ChangeNotifierProvider.value(value: push)],
      child: MaterialApp(theme: buildTheme(AppColors.light, Brightness.light), home: const SoundScreen()),
    ));
    expect(find.textContaining('falta configurar o Firebase'), findsOneWidget);
    for (final s in orderSounds) {
      expect(find.text(s.label), findsOneWidget);
    }
    await t.tap(find.text('Caixa registradora'));
    await t.pump();
    expect(push.soundId, 'caixa');
  });
}
