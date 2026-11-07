/*
 * Disegni botanici al tratto: ginepro, lavanda, genziana e pino mugo.
 * La geometria è in geometria.ts; qui solo il disegno e l'eventuale
 * animazione "a mano" (il tratto che si disegna), che si spegne con
 * prefers-reduced-motion (vedi .disegno--animato in stile.css).
 */
import type { CSSProperties } from 'react';

import {
  genziana,
  ginepro,
  lavanda,
  pinoMugo,
  rametto,
  type DisegnoTratti,
  type Rametto as DatiRametto,
} from './geometria';

// Calcolati una volta sola: il disegno è sempre lo stesso
const GINEPRO = ginepro();
const LAVANDA = lavanda();
const GENZIANA = genziana();
const PINO_MUGO = pinoMugo();
const RAMETTI: Record<'ginepro' | 'lavanda', DatiRametto> = {
  ginepro: rametto('ginepro'),
  lavanda: rametto('lavanda'),
};

const ritardo = (ms: number) => ({ '--ritardo': `${ms}ms` }) as CSSProperties;

interface Props {
  className?: string;
  /** Il tratto si disegna all'apertura della pagina. */
  animato?: boolean;
}

/** Rametto di ginepro comune con bacche (Juniperus communis). */
export function Ginepro({ className = '', animato = false }: Props) {
  return (
    <svg
      className={`disegno disegno--ginepro${animato ? ' disegno--animato' : ''} ${className}`}
      viewBox={`0 0 ${GINEPRO.larghezza} ${GINEPRO.altezza}`}
      role="img"
      aria-label="Rametto di ginepro con le bacche, disegnato a tratto"
    >
      {GINEPRO.rami.map((ramo, i) => (
        <g key={i}>
          <path className="disegno__legno" d={ramo.linea} pathLength={1} style={ritardo(i === 0 ? 0 : 250 + i * 110)} />
          <path className="disegno__aghi" d={ramo.aghi} pathLength={1} style={ritardo(500 + i * 110)} />
        </g>
      ))}
      {GINEPRO.bacche.map((bacca, i) => (
        <g key={i} className="disegno__bacca" style={ritardo(1700 + i * 90)}>
          <circle cx={bacca.cx} cy={bacca.cy} r={bacca.r} />
          <path className="disegno__segno" d={bacca.segno} />
          <path className="disegno__riflesso" d={bacca.riflesso} />
        </g>
      ))}
    </svg>
  );
}

/** Tre spighe di lavanda (Lavandula angustifolia). */
export function Lavanda({ className = '', animato = false }: Props) {
  return (
    <svg
      className={`disegno disegno--lavanda${animato ? ' disegno--animato' : ''} ${className}`}
      viewBox={`0 0 ${LAVANDA.larghezza} ${LAVANDA.altezza}`}
      role="img"
      aria-label="Tre spighe di lavanda, disegnate a tratto"
    >
      {LAVANDA.foglie.map((foglia, i) => (
        <path key={`f${i}`} className="disegno__foglia" d={foglia} pathLength={1} style={ritardo(i * 80)} />
      ))}
      {LAVANDA.steli.map((stelo, i) => (
        <path key={`s${i}`} className="disegno__legno" d={stelo} pathLength={1} style={ritardo(300 + i * 150)} />
      ))}
      <g className="disegno__fiori" style={ritardo(1300)}>
        {LAVANDA.fiori.map((fiore, i) => (
          <path key={i} d={fiore} />
        ))}
      </g>
    </svg>
  );
}

/** Il rametto piccolo della botanica di stagione: lavanda se è lavanda, altrimenti ginepro. */
export function Rametto({ botanica, className = '' }: { botanica: string; className?: string }) {
  const tipo = botanica.trim().toLowerCase().startsWith('lavand') ? 'lavanda' : 'ginepro';
  const disegno = RAMETTI[tipo];
  return (
    <svg
      className={`disegno disegno--rametto disegno--${tipo} ${className}`}
      viewBox={`0 0 ${disegno.larghezza} ${disegno.altezza}`}
      aria-hidden="true"
      focusable="false"
    >
      {disegno.linee.map((linea, i) => (
        <path key={`l${i}`} className="disegno__legno" d={linea} />
      ))}
      {disegno.pieni.map((pieno, i) => (
        <path key={`p${i}`} className={tipo === 'lavanda' ? 'disegno__fiore' : 'disegno__bacca-piena'} d={pieno} />
      ))}
    </svg>
  );
}

/** Disegno fatto di tratti e di forme piene (fiori, gemme, pigna). */
function Tratti({ disegno, tipo, etichetta, className }: { disegno: DisegnoTratti; tipo: string; etichetta: string; className: string }) {
  return (
    <svg
      className={`disegno disegno--${tipo} ${className}`}
      viewBox={`0 0 ${disegno.larghezza} ${disegno.altezza}`}
      role="img"
      aria-label={etichetta}
    >
      {disegno.linee.map((linea, i) => (
        <path key={`l${i}`} className="disegno__legno" d={linea} />
      ))}
      {disegno.pieni.map((pieno, i) => (
        <path key={`p${i}`} className="disegno__pieno" d={pieno} />
      ))}
    </svg>
  );
}

/** Genziana maggiore (Gentiana lutea), con le foglie nervate e i fiori a stella. */
export function Genziana({ className = '' }: { className?: string }) {
  return (
    <Tratti disegno={GENZIANA} tipo="genziana" etichetta="Uno stelo di genziana con foglie e fiori, disegnato a tratto" className={className} />
  );
}

/** Un ramo di pino mugo (Pinus mugo) con gli aghi, le gemme e una pigna. */
export function PinoMugo({ className = '' }: { className?: string }) {
  return (
    <Tratti disegno={PINO_MUGO} tipo="pino-mugo" etichetta="Un ramo di pino mugo con gli aghi e una pigna, disegnato a tratto" className={className} />
  );
}
