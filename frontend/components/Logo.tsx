import clsx from 'clsx';

// Logos da raykookie (public/brand). Não redesenhar nem trocar por texto: use sempre estes SVGs.
// principal: espaços largos (>= 120px) · compacta: espaços menores (>= 56px) · simbolo: quadrado pequeno.
// tone "auto" alterna claro/escuro pelo tema do app; "claro"/"escuro" fixam a versão pelo fundo da página;
// "sobre-rosa" é para fundo rosa da marca.
type Props = { variant?: 'principal' | 'compacta' | 'simbolo'; tone?: 'auto' | 'claro' | 'escuro' | 'sobre-rosa'; className?: string; imgClassName?: string };

export function Logo({ variant = 'principal', tone = 'auto', className, imgClassName = 'h-9' }: Props) {
  const img = (file: string, extra?: string) => (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={`/brand/raykookie-${file}.svg`} alt="raykookie" draggable={false} className={clsx('w-auto max-w-none', imgClassName, extra)} />
  );
  if (variant === 'simbolo') return <span className={clsx('inline-flex', className)}>{img('simbolo-cookie')}</span>;
  if (tone !== 'auto') return <span className={clsx('inline-flex', className)}>{img(`${variant}-${tone === 'claro' ? 'fundo-claro' : tone === 'escuro' ? 'fundo-escuro' : 'sobre-rosa'}`)}</span>;
  return (
    <span className={clsx('inline-flex', className)}>
      {img(`${variant}-fundo-claro`, 'dark:hidden')}
      {img(`${variant}-fundo-escuro`, 'hidden dark:block')}
    </span>
  );
}
