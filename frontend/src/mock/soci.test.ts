import { describe, expect, it } from 'vitest';

import { numeriBottiglie } from '../api/soci';
import { somma, tiraturaPrevista } from '../utilita/regole';
import { DATI_FINTI, datiFinti } from './soci';

const { area, edizioneEstate: estate } = DATI_FINTI;

describe('dati finti dell’area soci', () => {
  it('la tiratura segue la regola: kg × 1000 / grammi per bottiglia', () => {
    expect(tiraturaPrevista(estate.botanicaAnnunciataKg, estate.grammiPerBottiglia)).toBe(estate.tiraturaAnnunciata);
    expect(tiraturaPrevista(estate.botanicaDisponibileKg, estate.grammiPerBottiglia)).toBe(estate.tiratura);
  });

  it('le quote sommano alla tiratura, prima e dopo il ricalcolo', () => {
    expect(somma(estate.quoteAnnunciate)).toBe(estate.tiraturaAnnunciata);
    expect(somma(estate.quote)).toBe(estate.tiratura);
  });

  it('nel ricalcolo cala solo la vendita online: soci e bar restano', () => {
    expect(estate.quote.SOCI).toBe(estate.quoteAnnunciate.SOCI);
    expect(estate.quote.BAR).toBe(estate.quoteAnnunciate.BAR);
    expect(estate.quote.ONLINE).toBe(estate.quoteAnnunciate.ONLINE - (estate.tiraturaAnnunciata - estate.tiratura));
  });

  it('la prenotazione rispetta il massimo per socio e i numeri stanno nella tiratura', () => {
    const prenotazione = estate.prenotazione!;
    expect(prenotazione.numeri.length).toBeLessThanOrEqual(area.club.bottigliePerSocio);
    expect(new Set(prenotazione.numeri).size).toBe(prenotazione.numeri.length);
    for (const n of prenotazione.numeri) {
      expect(n).toBeGreaterThanOrEqual(1);
      expect(n).toBeLessThanOrEqual(estate.tiratura);
    }
    expect(prenotazione.edizione.tiratura).toBe(estate.tiratura);
  });

  it('ogni avviso parla di un’edizione che esiste', () => {
    for (const notifica of area.notifiche) {
      expect(notifica.idEdizione).toBe(estate.id);
    }
  });

  it('la tracciabilità va dalla ricetta al controllo qualità, in ordine di tempo', () => {
    expect(estate.tracciabilita.map((t) => t.tipo)).toEqual(['FORMULAZIONE', 'BOTANICA', 'CONTROLLO_QUALITA']);
    const date = estate.tracciabilita.map((t) => Date.parse(t.quando));
    expect([...date].sort((a, b) => a - b)).toEqual(date);
    const lavanda = estate.tracciabilita[0].dosi?.find((d) => d.botanica === estate.botanica);
    expect(lavanda?.grammiPerBottiglia).toBe(estate.grammiPerBottiglia);
  });

  it('un’edizione che non c’è risponde 404, come farà il servizio', async () => {
    await expect(datiFinti.edizione('non-esiste')).rejects.toMatchObject({ stato: 404 });
  });

  it('le risposte sono copie: modificarle non cambia i dati', async () => {
    const copia = await datiFinti.edizione(estate.id);
    copia.quote.SOCI = 0;
    expect(estate.quote.SOCI).toBe(300);
  });
});

describe('numeriBottiglie', () => {
  it('scrive i numeri come in italiano', () => {
    expect(numeriBottiglie([112])).toBe('n. 112');
    expect(numeriBottiglie([112, 113])).toBe('n. 112 e 113');
    expect(numeriBottiglie([110, 111, 112])).toBe('n. 110, 111 e 112');
    expect(numeriBottiglie([])).toBe('');
  });
});
