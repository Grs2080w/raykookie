import { ok, route } from '@/lib/api';
import { getDashboard } from '@/lib/dashboard';

export const GET = route(async (_req, user) => ok(await getDashboard(user.id, user.username)));
