// Datas no fuso da loja (o servidor do frontend pode estar em UTC).
export const TZ = 'America/Sao_Paulo';
export const todayBR = () => new Date().toLocaleDateString('sv-SE', { timeZone: TZ });
export const monthKeyBR = () => `${todayBR().slice(0, 7)}-01`;
