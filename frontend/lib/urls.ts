// Endereços públicos. A fonte é config/urls.json (na raiz do repositório); as variáveis de ambiente, se existirem, valem mais.
// Para mudar um endereço: edite o arquivo e faça um novo deploy.
import urls from '../../config/urls.json';

/** Endereço público do site e do cliente (sem barra no fim). */
export const SITE_URL = (process.env.SITE_URL || urls.SITE_URL).replace(/\/$/, '');

/** Host que serve só o painel de gestão. */
export const GESTAO_HOST = (process.env.GESTAO_HOST || urls.GESTAO_HOST).toLowerCase();

/** Endereço da API (sem barra no fim). */
export const API_BASE = (process.env.API_BASE || urls.API_BASE).replace(/\/$/, '');
