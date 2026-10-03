import type { Metadata } from 'next';
import { LegalPage } from '@/components/LegalPage';
import { LEGAL_DOCS } from '@shared/legal';

export const metadata: Metadata = { title: 'Termos de Serviço', description: LEGAL_DOCS.termos.summary };

export default function Page() { return <LegalPage doc={LEGAL_DOCS.termos} />; }
