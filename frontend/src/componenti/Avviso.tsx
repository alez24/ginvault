import type { ReactNode } from 'react';

interface Props {
  titolo: string;
  children?: ReactNode;
  /** "informazione" per le notizie, "problema" per errori e operazioni rifiutate. */
  tipo?: 'informazione' | 'problema';
  onChiudi?: () => void;
}

/** Riquadro per messaggi all'utente. Gli errori vengono letti subito dagli screen reader. */
export function Avviso({ titolo, children, tipo = 'informazione', onChiudi }: Props) {
  return (
    <div className={`avviso avviso--${tipo}`} role={tipo === 'problema' ? 'alert' : 'status'}>
      <div>
        <p className="avviso__titolo">{titolo}</p>
        {children && <div className="avviso__testo">{children}</div>}
      </div>
      {onChiudi && (
        <button type="button" className="avviso__chiudi" onClick={onChiudi} aria-label="Chiudi l’avviso">
          ×
        </button>
      )}
    </div>
  );
}
