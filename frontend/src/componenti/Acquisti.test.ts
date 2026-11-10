import { describe, expect, it } from 'vitest';

import { schedaAttiva } from './Acquisti';

describe('schede dell’area acquisti', () => {
  it('gli ordini: l’elenco, il nuovo ordine e il dettaglio', () => {
    expect(schedaAttiva('/acquisti')).toBe('/acquisti');
    expect(schedaAttiva('/acquisti/')).toBe('/acquisti');
    expect(schedaAttiva('/acquisti/ordini/nuovo')).toBe('/acquisti');
    expect(schedaAttiva('/acquisti/ordini/3f2c')).toBe('/acquisti');
  });

  it('lotti e fornitori, anche nelle loro pagine di dettaglio', () => {
    expect(schedaAttiva('/acquisti/lotti')).toBe('/acquisti/lotti');
    expect(schedaAttiva('/acquisti/lotti/a000')).toBe('/acquisti/lotti');
    expect(schedaAttiva('/acquisti/fornitori/f000')).toBe('/acquisti/fornitori');
  });

  it('nessuna scheda fuori dall’area', () => {
    expect(schedaAttiva('/acquisti/altro')).toBeUndefined();
    expect(schedaAttiva('/distilleria/piani')).toBeUndefined();
  });
});
