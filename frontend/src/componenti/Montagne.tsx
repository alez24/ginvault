/*
 * Le sagome delle montagne al posto di righe e riquadri: tre crinali uno davanti
 * all'altro (cielo, salvia e il colore della sezione che segue). I profili si
 * calcolano una volta sola, con un seme fisso: sono sempre uguali.
 */
import { crinale } from './illustrazioni/geometria';

/**
 * I fondi che un divisore può avere (variabili CSS con lo stesso nome). «sopra» è
 * il fondo dell'ultima sezione della pagina (lo decide il CSS); «trasparente»
 * lascia vedere la foto che c'è dietro.
 */
export type Fondo = 'neve' | 'avorio' | 'abete' | 'sopra' | 'trasparente';

const DIVISORI = [3, 21, 40, 57].map((seme) => ({
  lontano: crinale(seme, { altezza: 160, base: 0.78, ampiezza: 0.3, picchi: 6, pendenza: 4.2, minimo: 0.3 }),
  medio: crinale(seme + 1, { altezza: 160, base: 0.5, ampiezza: 0.26, picchi: 8, pendenza: 4.6, minimo: 0.16 }),
  vicino: crinale(seme + 2, {
    altezza: 160,
    base: 0.24,
    ampiezza: 0.12,
    picchi: 5,
    pendenza: 1.6,
    asperita: 0.025,
    minimo: 0.06,
  }),
}));

interface Props {
  /** Il fondo sopra le montagne: la sezione da cui si arriva. */
  da: Fondo;
  /** Il fondo davanti: la sezione che segue. */
  a: Fondo;
  /** Quale profilo usare, per non ripetere la stessa catena. */
  variante?: number;
  className?: string;
}

/** Il passaggio da una sezione all'altra: tre catene di montagne. Solo decorazione. */
export function Divisore({ da, a, variante = 0, className = '' }: Props) {
  const profilo = DIVISORI[variante % DIVISORI.length];
  return (
    <div className={`divisore divisore--da-${da} divisore--a-${a} ${className}`} aria-hidden="true">
      <svg viewBox="0 0 1440 160" preserveAspectRatio="none" focusable="false">
        <path className="divisore__lontano" d={profilo.lontano} />
        <path className="divisore__medio" d={profilo.medio} />
        <path className="divisore__vicino" d={profilo.vicino} />
      </svg>
    </div>
  );
}

/**
 * I tre crinali in fondo all'apertura della home, più alti di quelli dei divisori.
 * Il più vicino ha il colore della neve e non scende sotto un terzo dell'altezza:
 * copre la base della bottiglia, che così sta dentro il paesaggio.
 */
export const CRINALI_APERTURA = {
  lontano: crinale(8, { altezza: 200, base: 0.72, ampiezza: 0.32, picchi: 5, pendenza: 2.1, minimo: 0.2 }),
  medio: crinale(15, { altezza: 200, base: 0.6, ampiezza: 0.3, picchi: 6, pendenza: 3, minimo: 0.18 }),
  vicino: crinale(31, {
    altezza: 200,
    base: 0.6,
    ampiezza: 0.2,
    picchi: 4,
    pendenza: 1.4,
    asperita: 0.03,
    minimo: 0.36,
  }),
};

/** Un crinale basso e tenue in fondo alle intestazioni del back-office. */
export const CRINALE_TESTATA = {
  lontano: crinale(64, { altezza: 80, base: 0.62, ampiezza: 0.34, picchi: 9, pendenza: 7, minimo: 0.16 }),
  vicino: crinale(65, {
    altezza: 80,
    base: 0.26,
    ampiezza: 0.14,
    picchi: 6,
    pendenza: 2.4,
    asperita: 0.03,
    minimo: 0.08,
  }),
};
