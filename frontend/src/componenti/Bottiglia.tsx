/*
 * La bottiglia di GinVault, dalla foto in docs/ scontornata su fondo trasparente.
 * Due versioni WebP (il browser sceglie la larghezza giusta) e un PNG di riserva,
 * che scaricano solo i browser senza WebP.
 */
import bottigliaPng from '../assets/bottiglia.png';
import bottiglia262 from '../assets/bottiglia-262.webp';
import bottiglia524 from '../assets/bottiglia-524.webp';

interface Props {
  className?: string;
  /** Quanto è larga sullo schermo, per scegliere tra le due versioni. */
  sizes: string;
  /** Nell'apertura della home è la prima immagine: si carica subito. */
  subito?: boolean;
  /** Una seconda bottiglia uguale accanto alla prima: gli screen reader la saltano. */
  decorativa?: boolean;
}

export function Bottiglia({ className = '', sizes, subito = false, decorativa = false }: Props) {
  return (
    <picture className="bottiglia-cornice">
      <source type="image/webp" srcSet={`${bottiglia262} 262w, ${bottiglia524} 524w`} sizes={sizes} />
      <img
        className={`bottiglia-foto ${className}`}
        src={bottigliaPng}
        width={524}
        height={1322}
        alt={
          decorativa
            ? ''
            : 'La bottiglia di GinVault: vetro trasparente, tappo di legno ed etichetta crema con il bicchiere e le bacche di ginepro'
        }
        fetchPriority={subito ? 'high' : undefined}
        loading={subito ? undefined : 'lazy'}
        decoding="async"
      />
    </picture>
  );
}

/** Il cartellino appeso al collo della bottiglia: l'edizione e, sotto, il numero o la tiratura. */
export function Cartellino({ edizione, sotto, className = '' }: { edizione: string; sotto: string; className?: string }) {
  return (
    <p className={`cartellino ${className}`}>
      <span className="cartellino__edizione">{edizione}</span>
      <span className="cartellino__sotto">{sotto}</span>
    </p>
  );
}
