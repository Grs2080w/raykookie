'use client';
import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { Field, Input, Select } from '@/components/ui/form';
import { toast } from '@/components/ui/Toast';
import { cleanCpf, formatCpf, isValidCpf } from '@shared/cpf';
import { CARD_INSTALLMENTS_MIN_AMOUNT } from '@shared/shop';
import { money } from '@/lib/format';
import { AcceptedBrands } from '@/components/site/AcceptedBrands';
import type { CardBrand } from '@/lib/payment-config';

// Tipos mínimos do SDK do Mercado Pago (carregado via <script>, não é um pacote npm — ver loadMp()).
type MpField = { on: (event: 'binChange', cb: (d: { bin: string }) => void) => void; mount: (el: HTMLElement) => MpField };
type MpPayerCost = { installments: number; installment_rate: number; installment_amount: number };
type Mp = {
  fields: {
    create: (type: 'cardNumber' | 'expirationDate' | 'securityCode', opts: Record<string, unknown>) => MpField;
    createCardToken: (data: { cardholderName: string; identificationType: 'CPF'; identificationNumber: string }) => Promise<{ id: string }>;
  };
  getInstallments: (o: { amount: string; bin: string; locale: string }) => Promise<{ payment_method_id: string; payment_type_id?: string; payer_costs: MpPayerCost[] }[]>;
};
declare global { interface Window { MercadoPago?: new (key: string, opts?: Record<string, unknown>) => Mp } }

let mpScriptPromise: Promise<void> | null = null;
function loadMpScript(): Promise<void> {
  if (window.MercadoPago) return Promise.resolve();
  if (!mpScriptPromise) {
    mpScriptPromise = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'https://sdk.mercadopago.com/js/v2';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Não conseguimos carregar o pagamento por cartão agora.'));
      document.head.appendChild(s);
    });
  }
  return mpScriptPromise;
}

const fieldStyle = {
  style: {
    base: { fontSize: '15px', color: '#2a2320', fontFamily: 'inherit', '::placeholder': { color: '#9c938d' } },
    invalid: { color: '#c4432b' },
  },
};

export type CardFieldsResult = { card_token: string; card_payment_method_id: string; card_payment_type_id?: string; cpf: string; installments: number };
export type CardFieldsHandle = { tokenize: () => Promise<CardFieldsResult | null> };

/** Formulário de cartão com a nossa cara: só número, validade e CVV são campos do Mercado Pago (iframes
 *  minúsculos, para o dado sensível nunca passar pelo nosso código) — o resto (nome, CPF, parcelas, bordas,
 *  cores) é nosso. Parcelamento sem juros (só as opções com installment_rate = 0) e só acima de R$150. */
export const CardFields = forwardRef<CardFieldsHandle, { publicKey: string; amount: number; brands?: CardBrand[] }>(function CardFields({ publicKey, amount, brands = [] }, ref) {
  const mpRef = useRef<Mp | null>(null);
  const numberRef = useRef<HTMLDivElement>(null);
  const dateRef = useRef<HTMLDivElement>(null);
  const cvvRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  const [cardholderName, setCardholderName] = useState('');
  const [cpf, setCpf] = useState('');
  const [paymentMethodId, setPaymentMethodId] = useState('');
  const [paymentTypeId, setPaymentTypeId] = useState('');
  const [installments, setInstallments] = useState(1);
  const [options, setOptions] = useState<MpPayerCost[]>([]);
  const installmentsRef = useRef(1);
  installmentsRef.current = installments;

  useEffect(() => {
    let cancelled = false;
    loadMpScript().then(() => {
      if (cancelled || !window.MercadoPago || !numberRef.current || !dateRef.current || !cvvRef.current) return;
      const mp = new window.MercadoPago(publicKey, { locale: 'pt-BR' });
      mpRef.current = mp;
      const cardNumber = mp.fields.create('cardNumber', { ...fieldStyle, placeholder: 'Número do cartão' }).mount(numberRef.current);
      mp.fields.create('expirationDate', { ...fieldStyle, placeholder: 'MM/AA' }).mount(dateRef.current);
      mp.fields.create('securityCode', { ...fieldStyle, placeholder: 'CVV' }).mount(cvvRef.current);
      cardNumber.on('binChange', async ({ bin }) => {
        if (!bin) { setPaymentMethodId(''); setPaymentTypeId(''); setOptions([]); return; }
        // getPaymentMethods({bin}) NÃO filtra de verdade pelo bin (confirmado contra a API real) — quem
        // sabe a bandeira certa é este endpoint, usado sempre (mesmo abaixo do limite de parcelamento).
        try {
          const res = await mp.getInstallments({ amount: String(amount || 1), bin, locale: 'pt-BR' });
          const type = res[0]?.payment_type_id ?? '';
          setPaymentMethodId(res[0]?.payment_method_id ?? '');
          setPaymentTypeId(type);
          // só sem juros: a loja absorve a taxa, o preço nunca muda com a parcela escolhida; débito e pré-pago são à vista
          const semJuros = amount > CARD_INSTALLMENTS_MIN_AMOUNT && type === 'credit_card' ? (res[0]?.payer_costs ?? []).filter((p) => p.installment_rate === 0) : [];
          setOptions(semJuros);
          if (!semJuros.some((p) => p.installments === installmentsRef.current)) setInstallments(1);
        } catch { setPaymentMethodId(''); setPaymentTypeId(''); setOptions([]); }
      });
      setReady(true);
    }).catch((e) => toast.error(e.message));
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [publicKey]);

  useImperativeHandle(ref, () => ({
    async tokenize() {
      if (!mpRef.current || !ready) { toast.error('O pagamento por cartão ainda está carregando. Aguarde um instante.'); return null; }
      if (cardholderName.trim().length < 2) { toast.error('Informe o nome impresso no cartão.'); return null; }
      if (!isValidCpf(cpf)) { toast.error('Informe um CPF válido (exigido pelo Mercado Pago para pagar com cartão).'); return null; }
      if (!paymentMethodId) { toast.error('Não reconhecemos a bandeira desse cartão.'); return null; }
      try {
        const { id } = await mpRef.current.fields.createCardToken({ cardholderName: cardholderName.trim(), identificationType: 'CPF', identificationNumber: cleanCpf(cpf) });
        return { card_token: id, card_payment_method_id: paymentMethodId, ...(paymentTypeId ? { card_payment_type_id: paymentTypeId } : {}), cpf: cleanCpf(cpf), installments: paymentTypeId && paymentTypeId !== 'credit_card' ? 1 : installments };
      } catch {
        toast.error('Confira os dados do cartão e tente de novo.');
        return null;
      }
    },
  }), [ready, cardholderName, cpf, paymentMethodId, paymentTypeId, installments]);

  return (
    <div className="flex flex-col gap-4">
      <AcceptedBrands brands={brands} />
      <Field label="Número do cartão">{() => <div ref={numberRef} className="h-11 rounded-xl border border-line bg-surface px-3.5 py-2.5" />}</Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Validade">{() => <div ref={dateRef} className="h-11 rounded-xl border border-line bg-surface px-3.5 py-2.5" />}</Field>
        <Field label="CVV">{() => <div ref={cvvRef} className="h-11 rounded-xl border border-line bg-surface px-3.5 py-2.5" />}</Field>
      </div>
      <Field label="Nome impresso no cartão">{(id) => <Input id={id} autoComplete="cc-name" maxLength={60} value={cardholderName} onChange={(e) => setCardholderName(e.target.value)} />}</Field>
      <Field label="CPF" hint="Exigido pelo Mercado Pago para pagar com cartão. Não guardamos esse número.">
        {(id) => <Input id={id} inputMode="numeric" autoComplete="off" maxLength={14} placeholder="000.000.000-00" value={formatCpf(cpf)} onChange={(e) => setCpf(e.target.value)} />}
      </Field>
      {options.length > 0 && (
        <Field label="Parcelas" hint="Sem juros.">
          {(id) => (
            <Select id={id} value={installments} onChange={(e) => setInstallments(Number(e.target.value))}>
              {options.map((o) => <option key={o.installments} value={o.installments}>{o.installments}x de {money(amount / o.installments)}</option>)}
            </Select>
          )}
        </Field>
      )}
    </div>
  );
});
