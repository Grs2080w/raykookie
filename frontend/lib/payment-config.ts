'use client';
import { useEffect, useState } from 'react';

export type CardBrand = { id: string; name: string; type: 'credit_card' | 'debit_card' | 'prepaid_card'; logo: string };
export type PaymentConfig = { cardEnabled: boolean; publicKey: string | null; brands: CardBrand[]; statusScreenBrick: boolean };

const OFF: PaymentConfig = { cardEnabled: false, publicKey: null, brands: [], statusScreenBrick: false };

/** Lê /api/payment-config uma vez por tela. `null` = ainda carregando; falha = cartão desligado.
 *  `brands` e `statusScreenBrick` podem faltar em servidores mais antigos: aí viram "nenhuma bandeira" e "desligado". */
export function usePaymentConfig(): PaymentConfig | null {
  const [cfg, setCfg] = useState<PaymentConfig | null>(null);
  useEffect(() => {
    let alive = true;
    fetch('/api/payment-config')
      .then((r) => r.json())
      .then((j) => alive && setCfg({ ...OFF, ...j, brands: Array.isArray(j.brands) ? j.brands : [] }))
      .catch(() => alive && setCfg(OFF));
    return () => { alive = false; };
  }, []);
  return cfg;
}
