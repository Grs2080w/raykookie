import { InputError, ok, route, str } from '@/lib/api';
import { q } from '@/lib/db';
import { DEFAULT_ORDER_SOUND, isOrderSound } from '@shared/push';

function body(b: any) {
  const token = str(b?.token).trim();
  if (token.length < 20 || token.length > 4096) throw new InputError('Token do aparelho inválido.');
  const sound = b?.sound === undefined ? undefined : b.sound;
  if (sound !== undefined && !isOrderSound(sound)) throw new InputError('Som desconhecido.');
  return { token, sound: sound as string | undefined };
}

// Registra (ou atualiza o som de) um celular que vai receber o push de novos pedidos.
export const POST = route(async (req, user) => {
  const { token, sound } = body(await req.json().catch(() => null));
  await q(
    `INSERT INTO device_tokens(user_id, token, platform, sound) VALUES ($1,$2,'android',$3)
     ON CONFLICT (token) DO UPDATE SET user_id = $1, sound = COALESCE($4, device_tokens.sound), last_seen = now()`,
    [user.id, token, sound ?? DEFAULT_ORDER_SOUND, sound ?? null],
  );
  return ok();
});

// Chamado no logout: este celular para de receber os avisos.
export const DELETE = route(async (req, user) => {
  const { token } = body({ token: new URL(req.url).searchParams.get('token') });
  await q('DELETE FROM device_tokens WHERE token = $1 AND user_id = $2', [token, user.id]);
  return ok();
});
