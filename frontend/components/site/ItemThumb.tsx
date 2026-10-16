import clsx from 'clsx';
import { placeholderTone } from '@/lib/menu-view';
import type { MenuItem } from '@shared/types';

/** Miniatura quadrada de um item do cardápio (área do cliente): a foto ou, sem foto, o símbolo do cookie sobre um tom do doce. */
export function ItemThumb({ item, className }: { item: MenuItem; className?: string }) {
  return (
    <span className={clsx('rk-thumb', className)} aria-hidden>
      {item.image ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={item.image} alt="" width={96} height={96} loading="lazy" decoding="async" />
      ) : (
        <span className="rk-ph" data-tone={placeholderTone(item)}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/brand/raykookie-simbolo-cookie.svg" alt="" width={48} height={48} loading="lazy" decoding="async" />
        </span>
      )}
    </span>
  );
}
