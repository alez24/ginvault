/*
 * Fotografie da Unsplash (licenza Unsplash: uso libero, anche commerciale).
 * Autori e pagine originali sono nel README, sezione «Immagini».
 * Scaricate già in webp alle larghezze che servono; srcSet lascia scegliere al browser.
 */
import paesaggioAlta900 from './paesaggio-alta-900.webp';
import paesaggioLarga1400 from './paesaggio-larga-1400.webp';
import paesaggioLarga2400 from './paesaggio-larga-2400.webp';
import prato500 from './prato-500.webp';
import prato800 from './prato-800.webp';

export interface Foto {
  src: string;
  srcSet: string;
  larghezza: number;
  altezza: number;
  alt: string;
}

/**
 * Le Alpi dal Titlis, sopra Engelberg (Laurin Vontobel): cielo chiaro in alto per il
 * titolo, crinali a strati sotto. Larga per lo schermo, alta (ritagliata sulle cime)
 * per il telefono. Decorativa: il testo alternativo è vuoto.
 */
export const PAESAGGIO = {
  larga: {
    src: paesaggioLarga1400,
    srcSet: `${paesaggioLarga1400} 1400w, ${paesaggioLarga2400} 2400w`,
    larghezza: 2400,
    altezza: 1600,
    alt: '',
  },
  alta: { src: paesaggioAlta900, srcSet: `${paesaggioAlta900} 900w`, larghezza: 900, altezza: 1500, alt: '' },
} satisfies Record<string, Foto>;

/** Prato fiorito sotto il Großglockner, Kals (the iop). */
export const PRATO: Foto = {
  src: prato800,
  srcSet: `${prato500} 500w, ${prato800} 800w`,
  larghezza: 800,
  altezza: 1200,
  alt: 'Un pascolo d’alta quota con fiori di campo, e dietro una cima di roccia sotto il cielo blu',
};
