import Link from 'next/link';
export default function NotFound() {
  return (
    <div className="grid min-h-dvh place-items-center px-6 text-center">
      <div>
        <p className="font-display text-7xl font-semibold text-pink">404</p>
        <h1 className="font-display mt-2 text-2xl font-semibold">Página não encontrada</h1>
        <p className="mt-1 text-muted">O endereço pode ter mudado ou o item foi excluído.</p>
        <Link href="/" className="mt-6 inline-block rounded-xl bg-pink px-5 py-2.5 font-semibold text-on-pink hover:bg-pink-strong">Voltar ao início</Link>
      </div>
    </div>
  );
}
