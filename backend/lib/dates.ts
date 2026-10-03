// Datas no fuso da loja (o servidor pode estar em UTC).
export const TZ = 'America/Sao_Paulo';
export const todayBR = () => new Date().toLocaleDateString('sv-SE', { timeZone: TZ });
/** Primeiro dia do mês atual, "AAAA-MM-01". */
export const monthKeyBR = () => `${todayBR().slice(0, 7)}-01`;
