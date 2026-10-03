import { q, q1 } from './db';
import { cleanText } from '@shared/text';

export type PickupAddress = {
  id: number; label: string; cep: string; street: string; number: string; complement: string; district: string; city: string;
  instructions: string; is_active: boolean;
};
export type PickupView = PickupAddress & { address: string };

export const MAX_PICKUP = 20; // endereços guardados por usuário

/** "Rua das Flores, 120 (apto 4) - Jardim Paulista, São Paulo" */
export function formatAddress(a: Pick<PickupAddress, 'street' | 'number' | 'complement' | 'district' | 'city'>): string {
  return `${a.street}, ${a.number}${a.complement ? ` (${a.complement})` : ''} - ${a.district}, ${a.city}`;
}

/** Texto que fica no pedido: endereço e, se houver, as instruções de como chegar. */
export function formatPickupLine(a: Pick<PickupAddress, 'street' | 'number' | 'complement' | 'district' | 'city' | 'instructions'>): string {
  return a.instructions ? `${formatAddress(a)} · ${a.instructions}` : formatAddress(a);
}

export const withAddress = (a: PickupAddress): PickupView => ({ ...a, address: formatAddress(a) });

/** Confere o formulário do endereço de retirada. */
export function parsePickup(b: any): { error: string } | { value: Omit<PickupAddress, 'id' | 'is_active'> } {
  const street = cleanText(b?.street).slice(0, 100), number = cleanText(b?.number).slice(0, 10);
  const district = cleanText(b?.district).slice(0, 60), city = cleanText(b?.city).slice(0, 60);
  const cep = cleanText(b?.cep).replace(/\D/g, '').slice(0, 8);
  if (cep && cep.length !== 8) return { error: 'O CEP precisa ter 8 números.' };
  if (!street) return { error: 'Informe a rua.' };
  if (!number) return { error: 'Informe o número (ou S/N, se não tiver).' };
  if (!district) return { error: 'Informe o bairro.' };
  if (!city) return { error: 'Informe a cidade.' };
  const label = cleanText(b?.label).slice(0, 40) || street;
  return { value: { label, cep, street, number, complement: cleanText(b?.complement).slice(0, 60), district, city, instructions: cleanText(b?.instructions).slice(0, 200) } };
}

export async function listPickup(userId: number): Promise<PickupView[]> {
  const rows = await q<PickupAddress>('SELECT id, label, cep, street, number, complement, district, city, instructions, is_active FROM pickup_addresses WHERE user_id = $1 ORDER BY is_active DESC, lower(label), id', [userId]);
  return rows.map(withAddress);
}

/** O endereço de retirada ativo da loja (null se a confeiteira ainda não escolheu nenhum). */
export async function getActivePickup(userId: number): Promise<PickupView | null> {
  const a = await q1<PickupAddress>('SELECT id, label, cep, street, number, complement, district, city, instructions, is_active FROM pickup_addresses WHERE user_id = $1 AND is_active', [userId]);
  return a ? withAddress(a) : null;
}
