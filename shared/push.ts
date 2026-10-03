/** Sons de "novo pedido" do app de gestão. O id vira o canal Android `pedido_<id>` e o arquivo res/raw/pedido_<id>.wav. */
export const ORDER_SOUNDS = [
  { id: 'sino', label: 'Sino' },
  { id: 'caixa', label: 'Caixa registradora' },
  { id: 'campainha', label: 'Campainha' },
  { id: 'suave', label: 'Suave' },
  { id: 'alerta', label: 'Alerta' },
] as const;

export type OrderSoundId = (typeof ORDER_SOUNDS)[number]['id'];
export const DEFAULT_ORDER_SOUND: OrderSoundId = 'sino';

export const isOrderSound = (v: unknown): v is OrderSoundId => ORDER_SOUNDS.some((s) => s.id === v);
export const orderChannelId = (sound: string) => `pedido_${isOrderSound(sound) ? sound : DEFAULT_ORDER_SOUND}`;
