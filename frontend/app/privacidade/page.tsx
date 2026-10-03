import type { Metadata } from 'next';
import { LegalPage } from '@/components/LegalPage';
import { LEGAL_DOCS } from '@shared/legal';

export const metadata: Metadata = { title: 'Política de Privacidade', description: LEGAL_DOCS.privacidade.summary };

export default function Page() { return <LegalPage doc={LEGAL_DOCS.privacidade} />; }
