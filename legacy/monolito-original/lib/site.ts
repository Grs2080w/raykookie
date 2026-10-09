// Endereço público do site (o servidor roda atrás de proxy, então não dá para confiar no host da requisição).
export const SITE_URL = (process.env.SITE_URL || 'https://raykookie.kntap.com.br').replace(/\/$/, '');
export const siteUrl = (path = '/') => `${SITE_URL}${path}`;
