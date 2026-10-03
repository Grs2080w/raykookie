/// Sons de "novo pedido". Os ids precisam bater com shared/push.ts (backend) e com os arquivos
/// `res/raw/pedido_ID.wav` e `assets/sounds/pedido_ID.wav` (gerados por tool/gen_sounds.py).
class OrderSound {
  const OrderSound(this.id, this.label);
  final String id;
  final String label;
  String get channelId => 'pedido_$id';
  String get asset => 'sounds/pedido_$id.wav';
}

const orderSounds = [
  OrderSound('sino', 'Sino'),
  OrderSound('caixa', 'Caixa registradora'),
  OrderSound('campainha', 'Campainha'),
  OrderSound('suave', 'Suave'),
  OrderSound('alerta', 'Alerta'),
];

const defaultOrderSoundId = 'sino';

OrderSound orderSoundById(String? id) =>
    orderSounds.firstWhere((s) => s.id == id, orElse: () => orderSounds.first);
