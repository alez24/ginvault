/*
 * Le quote di un'edizione come bottiglie disegnate: ogni bottiglia ne vale 10.
 * Dopo un ricalcolo le bottiglie tolte si svuotano una dopo l'altra (dall'ultima)
 * e restano in contorno; accanto al canale compare la differenza (es. −60).
 * Con prefers-reduced-motion si vede subito lo stato finale.
 */
import type { CSSProperties } from 'react';

import { CANALI, NOMI_CANALI, type Canale } from '../api/edizioni';
import { differenza, numero } from '../utilita/numeri';

/** Quante bottiglie vere vale una bottiglia disegnata. */
export const BOTTIGLIE_PER_DISEGNO = 10;

/** Prima di svuotare si aspetta che la pagina abbia finito di scorrere fino alle quote. */
const ATTESA_MS = 650;
const PASSO_MS = 260;

interface Livello {
  /** Livello del liquido prima del ricalcolo (0–1). */
  da: number;
  /** Livello adesso (0–1). */
  a: number;
  /** Ordine nell'animazione, solo per le bottiglie che cambiano. */
  ordine?: number;
}

const livello = (quante: number, indice: number) =>
  Math.min(1, Math.max(0, (quante - indice * BOTTIGLIE_PER_DISEGNO) / BOTTIGLIE_PER_DISEGNO));

/**
 * I livelli delle bottiglie di un canale. Le bottiglie che cambiano hanno un
 * ordine: se il canale cala si svuotano dall'ultima, se cresce si riempiono
 * dalla prima libera.
 */
export function livelli(adesso: number, prima: number = adesso): Livello[] {
  const quante = Math.ceil(Math.max(adesso, prima) / BOTTIGLIE_PER_DISEGNO);
  const elenco: Livello[] = Array.from({ length: quante }, (_, i) => ({ da: livello(prima, i), a: livello(adesso, i) }));
  const cambiano = elenco.map((l, i) => (l.da !== l.a ? i : -1)).filter((i) => i >= 0);
  const ordinate = adesso < prima ? [...cambiano].reverse() : cambiano;
  ordinate.forEach((indice, ordine) => {
    elenco[indice].ordine = ordine;
  });
  return elenco;
}

function Bottiglia({ canale, livello: { da, a, ordine } }: { canale: Canale; livello: Livello }) {
  const animata = ordine !== undefined;
  const stile = {
    '--da': da,
    '--a': a,
    '--ritardo': `${ATTESA_MS + (ordine ?? 0) * PASSO_MS}ms`,
  } as CSSProperties;
  const classi = [
    'bottiglia',
    `bottiglia--${canale.toLowerCase()}`,
    animata ? 'bottiglia--animata' : '',
    a === 0 ? 'bottiglia--vuota' : '',
  ].join(' ');
  return (
    <svg className={classi} viewBox="0 0 12 30" aria-hidden="true" focusable="false" style={stile}>
      <rect className="bottiglia__liquido" x="2.2" y="12.4" width="7.6" height="16.1" rx="0.9" />
      <rect className="bottiglia__etichetta" x="2.2" y="17.6" width="7.6" height="4.2" />
      <path
        className="bottiglia__vetro"
        d="M4.7 3.3 V7.3 C4.7 9.1 1.3 9.5 1.3 12.3 V27.9 Q1.3 29.3 2.7 29.3 H9.3 Q10.7 29.3 10.7 27.9 V12.3 C10.7 9.5 7.3 9.1 7.3 7.3 V3.3"
      />
      <rect className="bottiglia__tappo" x="4.1" y="0.7" width="3.8" height="2.6" rx="0.5" />
    </svg>
  );
}

interface Props {
  quote: Record<Canale, number>;
  /** Senza le prenotate (in home) sotto le bottiglie non c'è la nota. */
  prenotate?: Record<Canale, number>;
  /** Le quote prima del ricalcolo: se ci sono, la differenza si anima. */
  prima?: Record<Canale, number>;
}

/** Le tre quote dell'edizione, ognuna con le sue bottiglie. */
export function QuoteInBottiglie({ quote, prenotate, prima }: Props) {
  return (
    <ul className="quote">
      {CANALI.map((canale) => {
        const elenco = livelli(quote[canale], prima?.[canale]);
        const scarto = prima ? quote[canale] - prima[canale] : 0;
        const fineAnimazione = ATTESA_MS + elenco.filter((l) => l.ordine !== undefined).length * PASSO_MS + 250;
        return (
          <li key={canale} className={`quota quota--${canale.toLowerCase()}`}>
            <div className="quota__testa">
              <span className="quota__nome">{NOMI_CANALI[canale]}</span>
              <span className="quota__numero">{numero(quote[canale])}</span>
              {scarto !== 0 && (
                <span
                  className="quota__scarto"
                  style={{ '--ritardo': `${fineAnimazione}ms` } as CSSProperties}
                  aria-label={`${numero(Math.abs(scarto))} ${scarto < 0 ? 'in meno' : 'in più'}`}
                >
                  {differenza(scarto)}
                </span>
              )}
            </div>
            <div className="quota__bottiglie">
              {elenco.map((l, i) => (
                <Bottiglia key={i} canale={canale} livello={l} />
              ))}
            </div>
            {prenotate && (
              <p className="quota__nota">
                {prenotate[canale] > 0 ? `${numero(prenotate[canale])} già prenotate` : 'Nessuna prenotata'}
              </p>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** Le bottiglie di tutti i canali su una sola mensola, piccole: per gli elenchi. */
export function Mensola({ quote }: { quote: Record<Canale, number> }) {
  const totale = CANALI.reduce((s, canale) => s + quote[canale], 0);
  return (
    <div
      className="mensola"
      role="img"
      aria-label={`${CANALI.map((canale) => `${NOMI_CANALI[canale]} ${quote[canale]}`).join(', ')}: ${totale} bottiglie`}
    >
      {CANALI.flatMap((canale) => livelli(quote[canale]).map((l, i) => <Bottiglia key={`${canale}${i}`} canale={canale} livello={l} />))}
    </div>
  );
}
