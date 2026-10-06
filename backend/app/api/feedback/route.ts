import { ok, route } from '@/lib/api';
import { listFeedback } from '@/lib/feedback';

// Lista da confeiteira, mais recentes primeiro.
export const GET = route(async (_req, user) => ok({ list: await listFeedback(user.id) }));
