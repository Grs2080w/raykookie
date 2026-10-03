import { ok } from '@/lib/api';

// Configuração pública da área do cliente (sem segredos).
export async function GET() { return ok({ googleEnabled: !!(process.env.GOOGLE_CLIENT_ID && process.env.GOOGLE_CLIENT_SECRET) }); }
