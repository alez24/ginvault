import { describe, expect, it } from 'vitest';

import type { Edizione } from '../api/edizioni';
import {
  bottigliePossibili,
  controllaDosi,
  controllaKg,
  controllaQuota,
  quoteSuggerite,
  ricalcoloPrevisto,
  tiraturaPrevista,
  type RigaDose,
} from './regole';

function edizione(modifiche: Partial<Edizione> = {}): Edizione {
  return {
    id: 'e1',
    formulazione: { id: 'f1', versione: 2 },
    botanicaDiStagione: 'lavanda',
    grammiPerBottiglia: 20,
    botanicaDisponibileKg: 12,
    tiratura: 600,
    quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
    prenotate: { SOCI: 0, BAR: 0, ONLINE: 0 },
    creataIl: '2026-06-01T10:00:00Z',
    ...modifiche,
  };
}

describe('tiratura (Tiratura.da)', () => {
  it('kg × 1000 / grammi, per difetto, al massimo 600', () => {
    expect(tiraturaPrevista(12, 20)).toBe(600);
    expect(tiraturaPrevista(10.8, 20)).toBe(540);
    expect(tiraturaPrevista(13, 20)).toBe(600);
    expect(bottigliePossibili(13, 20)).toBe(650);
    expect(tiraturaPrevista(10.819, 20)).toBe(540);
  });

  it('non si fa ingannare dalla virgola mobile (1,005 × 1000 = 1004,999… in JavaScript)', () => {
    expect(Math.floor((1.005 * 1000) / 1)).toBe(1004); // il conto ingenuo sbaglia
    expect(bottigliePossibili(1.005, 1)).toBe(1005);
    expect(Math.floor((1.1 * 1000) / 1.1)).toBe(999);
    expect(bottigliePossibili(1.1, 1.1)).toBe(1000);
  });

  it('0 quando non si riempie nemmeno una bottiglia', () => {
    expect(tiraturaPrevista(0.019, 20)).toBe(0);
    expect(tiraturaPrevista(0.02, 20)).toBe(1);
    expect(tiraturaPrevista(0, 20)).toBe(0);
  });
});

describe('divisione di partenza delle quote', () => {
  it('metà ai soci, un quarto ai bar, il resto online', () => {
    expect(quoteSuggerite(600)).toEqual({ SOCI: 300, BAR: 150, ONLINE: 150 });
    expect(quoteSuggerite(540)).toEqual({ SOCI: 270, BAR: 135, ONLINE: 135 });
    expect(quoteSuggerite(7)).toEqual({ SOCI: 3, BAR: 1, ONLINE: 3 });
  });
});

describe('ricalcolo (Edizione.ricalcola)', () => {
  it('la storia di Marco: 10,8 kg -> 540 bottiglie, tolte 60 a ONLINE', () => {
    expect(ricalcoloPrevisto(edizione(), 10.8)).toEqual({
      esito: 'possibile',
      tiratura: 540,
      quote: { SOCI: 300, BAR: 150, ONLINE: 90 },
    });
  });

  it('finito ONLINE si toglie da BAR, mai dai soci', () => {
    expect(ricalcoloPrevisto(edizione(), 6)).toEqual({
      esito: 'possibile',
      tiratura: 300,
      quote: { SOCI: 300, BAR: 0, ONLINE: 0 },
    });
    expect(ricalcoloPrevisto(edizione(), 5)).toEqual({
      esito: 'non-riducibile',
      tiratura: 250,
      daTogliere: 350,
      libere: 300,
    });
  });

  it('le bottiglie prenotate non si tolgono', () => {
    const conPrenotate = edizione({ prenotate: { SOCI: 0, BAR: 0, ONLINE: 100 } });
    expect(ricalcoloPrevisto(conPrenotate, 10.8)).toEqual({
      esito: 'possibile',
      tiratura: 540,
      quote: { SOCI: 300, BAR: 140, ONLINE: 100 },
    });
  });

  it('se la tiratura sale le bottiglie in più vanno a ONLINE; se non cambia restano le quote', () => {
    const ridotta = edizione({ tiratura: 540, botanicaDisponibileKg: 10.8, quote: { SOCI: 300, BAR: 150, ONLINE: 90 } });
    expect(ricalcoloPrevisto(ridotta, 12)).toEqual({
      esito: 'possibile',
      tiratura: 600,
      quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
    });
    expect(ricalcoloPrevisto(edizione(), 13)).toEqual({
      esito: 'possibile',
      tiratura: 600,
      quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
    });
  });

  it('con troppo poca botanica non si riempie nemmeno una bottiglia', () => {
    expect(ricalcoloPrevisto(edizione(), 0.01)).toEqual({ esito: 'nessuna-bottiglia' });
  });
});

let chiave = 0;
const riga = (botanica: string, grammi: string): RigaDose => ({ chiave: ++chiave, botanica, grammi });

describe('dosi di una prova (Dose e Formulazione.verificaDosi)', () => {
  it('righe valide: virgola decimale accettata, righe vuote ignorate', () => {
    const controllo = controllaDosi([riga('ginepro', '24'), riga('', ''), riga(' Lavanda ', '12,5')], 'lavanda');
    expect(controllo.dosi).toEqual([
      { botanica: 'ginepro', grammiPerBottiglia: 24 },
      { botanica: 'Lavanda', grammiPerBottiglia: 12.5 },
    ]);
    expect(controllo.generali).toEqual([]);
  });

  it('serve la botanica di stagione', () => {
    const controllo = controllaDosi([riga('ginepro', '24')], 'lavanda');
    expect(controllo.dosi).toBeNull();
    expect(controllo.generali).toEqual(['Manca la botanica di stagione (lavanda): serve a calcolare la tiratura.']);
  });

  it('serve almeno una dose', () => {
    expect(controllaDosi([riga('', '')], 'lavanda').generali).toEqual(['Serve almeno una dose.']);
  });

  it('la stessa botanica due volte (maiuscole e spazi non contano)', () => {
    const doppia = riga('Lavanda ', '10');
    const controllo = controllaDosi([riga('lavanda', '12'), doppia], 'lavanda');
    expect(controllo.dosi).toBeNull();
    expect(controllo.righe[doppia.chiave].botanica).toMatch(/compare già sopra/);
  });

  it('grammi: obbligatori, maggiori di zero, al massimo due decimali', () => {
    const vuoti = riga('ginepro', '');
    const zero = riga('coriandolo', '0');
    const negativi = riga('angelica', '-3');
    const troppi = riga('lavanda', '12,345');
    const parola = riga('limone', 'tanti');
    const controllo = controllaDosi([vuoti, zero, negativi, troppi, parola], 'lavanda');
    expect(controllo.dosi).toBeNull();
    expect(controllo.righe[vuoti.chiave].grammi).toBe('Scrivi i grammi per bottiglia.');
    expect(controllo.righe[zero.chiave].grammi).toBe('Devono essere più di zero.');
    expect(controllo.righe[negativi.chiave].grammi).toBe('Devono essere più di zero.');
    expect(controllo.righe[troppi.chiave].grammi).toBe('Al massimo due decimali.');
    expect(controllo.righe[parola.chiave].grammi).toBe('Scrivi un numero, per esempio 12,5.');
  });

  it('i grammi senza botanica', () => {
    const senzaNome = riga('', '12');
    expect(controllaDosi([riga('lavanda', '20'), senzaNome], 'lavanda').righe[senzaNome.chiave].botanica).toBe(
      'Scrivi la botanica.',
    );
  });
});

describe('kg e quote dell’edizione', () => {
  it('kg al grammo, con la virgola', () => {
    expect(controllaKg('10,8')).toBe(10.8);
    expect(controllaKg(' 12 ')).toBe(12);
    expect(controllaKg('10,8000')).toBe(10.8);
    expect(controllaKg('10,8001')).toBe('Al massimo tre decimali: i kg si indicano al grammo.');
    expect(controllaKg('')).toBe('Scrivi i kg disponibili.');
    expect(controllaKg('0')).toBe('Devono essere più di zero.');
    expect(controllaKg('dieci')).toBe('Scrivi un numero, per esempio 10,8.');
  });

  it('quote intere, zero compreso', () => {
    expect(controllaQuota('0')).toBe(0);
    expect(controllaQuota('150')).toBe(150);
    expect(controllaQuota('-1')).toBe('Un numero intero di bottiglie, zero o più.');
    expect(controllaQuota('1,5')).toBe('Un numero intero di bottiglie, zero o più.');
    expect(controllaQuota('')).toBe('Scrivi quante bottiglie (anche 0).');
  });
});
