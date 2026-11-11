import { describe, expect, it } from 'vitest';

import {
  bottiglie,
  cifre,
  differenza,
  data,
  edizioneDellaStagione,
  euro,
  giornoConNome,
  giornoDopo,
  giorni,
  giorniTra,
  giornoValido,
  ilGiorno,
  kg,
  oggi,
  leggiNumero,
  maiuscola,
  numero,
  ora,
  orario,
  partiDelGiorno,
  perCampo,
  traQuanto,
} from './numeri';

describe('numeri scritti dall’utente', () => {
  it('accetta la virgola e il punto decimale', () => {
    expect(leggiNumero('10,8')).toBe(10.8);
    expect(leggiNumero('10.8')).toBe(10.8);
    expect(leggiNumero(' 12 ')).toBe(12);
  });

  it('rifiuta quello che non è un numero semplice', () => {
    expect(leggiNumero('')).toBeNull();
    expect(leggiNumero('1e3')).toBeNull();
    expect(leggiNumero('12kg')).toBeNull();
    expect(leggiNumero('1.000,5')).toBeNull();
    expect(leggiNumero(',5')).toBeNull();
  });

  it('conta le cifre come Bean Validation (@Digits): zeri iniziali e finali non contano', () => {
    expect(cifre('012,500')).toEqual({ intere: 2, decimali: 1 });
    expect(cifre('0,125')).toEqual({ intere: 1, decimali: 3 });
    expect(cifre('600')).toEqual({ intere: 3, decimali: 0 });
  });

  it('riporta un numero nel campo con la virgola', () => {
    expect(perCampo(12.5)).toBe('12,5');
    expect(perCampo(20)).toBe('20');
  });
});

describe('numeri mostrati', () => {
  it('all’italiana', () => {
    expect(numero(10.8)).toBe('10,8');
    expect(numero(1234)).toBe('1234');
    expect(numero(12000)).toBe('12.000');
  });

  it('differenze con il segno', () => {
    expect(differenza(-60)).toBe('−60');
    expect(differenza(60)).toBe('+60');
    expect(differenza(0)).toBe('0');
    expect(differenza(-1.2)).toBe('−1,2');
  });

  it('bottiglie al singolare e al plurale', () => {
    expect(bottiglie(1)).toBe('1 bottiglia');
    expect(bottiglie(540)).toBe('540 bottiglie');
  });

  it('date con l’articolo giusto', () => {
    expect(ilGiorno('2026-11-05T10:00:00Z')).toBe('il 5 novembre 2026');
    expect(ilGiorno('2026-10-08T10:00:00Z')).toBe('l’8 ottobre 2026');
    expect(ilGiorno('2026-03-11T10:00:00Z')).toBe('l’11 marzo 2026');
    expect(ilGiorno('2026-03-18T10:00:00Z')).toBe('il 18 marzo 2026');
  });

  it('il nome dell’edizione dalla stagione', () => {
    expect(edizioneDellaStagione('2026-07-08T10:00:00Z')).toBe('Edizione d’estate 2026');
    expect(edizioneDellaStagione('2026-10-08T10:00:00Z')).toBe('Edizione d’autunno 2026');
    expect(edizioneDellaStagione('2026-04-01T10:00:00Z')).toBe('Edizione di primavera 2026');
    expect(edizioneDellaStagione('2026-01-15T10:00:00Z')).toBe('Edizione d’inverno 2026');
    // A Roma è già il 1° giugno
    expect(edizioneDellaStagione('2026-05-31T23:30:00Z')).toBe('Edizione d’estate 2026');
  });

  it('maiuscola iniziale', () => {
    expect(maiuscola('lavanda')).toBe('Lavanda');
    expect(maiuscola('')).toBe('');
  });
});

describe('appuntamenti del club, con l’ora di Roma', () => {
  const inizio = '2026-08-22T18:00:00+02:00';

  it('il giorno con il nome', () => {
    expect(giornoConNome(inizio)).toBe('sabato 22 agosto 2026');
    expect(partiDelGiorno(inizio)).toEqual({ settimana: 'sabato', giorno: '22', mese: 'agosto', anno: '2026' });
  });

  it('l’ora senza i minuti quando è piena', () => {
    expect(ora(inizio)).toBe('18');
    expect(ora('2026-08-22T18:30:00+02:00')).toBe('18:30');
    expect(orario(inizio, '2026-08-22T20:00:00+02:00')).toBe('dalle 18 alle 20');
  });

  it('un orario in UTC diventa l’ora italiana', () => {
    expect(ora('2026-08-22T16:00:00Z')).toBe('18');
  });
});


describe('giorni senza ora (le date del magazzino)', () => {
  it('"2026-08-06" resta il 6 agosto in qualsiasi fuso', () => {
    expect(data('2026-08-06')).toBe('6 agosto 2026');
    expect(ilGiorno('2026-08-06')).toBe('il 6 agosto 2026');
    expect(ilGiorno('2026-07-31')).toBe('il 31 luglio 2026');
  });

  it('oggi è il giorno di Roma, come per il magazzino', () => {
    // 23:30 UTC del 10 novembre: a Roma è già l’11
    expect(oggi(new Date('2026-11-10T23:30:00Z'))).toBe('2026-11-11');
    expect(oggi(new Date('2026-08-06T09:00:00Z'))).toBe('2026-08-06');
  });

  it('giorni tra due date, e giorni dopo', () => {
    expect(giorniTra('2026-07-31', '2026-08-06')).toBe(6);
    expect(giorniTra('2026-08-06', '2026-07-31')).toBe(-6);
    expect(giornoDopo('2026-07-31', 1)).toBe('2026-08-01');
    // il cambio dell’ora legale non conta
    expect(giorniTra('2026-10-24', '2026-10-26')).toBe(2);
  });

  it('solo date vere nel formato AAAA-MM-GG', () => {
    expect(giornoValido('2026-08-06')).toBe(true);
    expect(giornoValido('2026-02-30')).toBe(false);
    expect(giornoValido('6/8/2026')).toBe(false);
    expect(giornoValido('')).toBe(false);
  });

  it('kg e giorni da leggere', () => {
    expect(kg(10.8)).toBe('10,8 kg');
    expect(giorni(1)).toBe('1 giorno');
    expect(giorni(6)).toBe('6 giorni');
  });
});

describe('prezzi e scadenze del pagamento', () => {
  it('euro all’italiana, con lo spazio che non va a capo', () => {
    expect(euro(45)).toBe('45,00 €');
    expect(euro(2 * 52)).toBe('104,00 €');
  });

  it('quanto manca per pagare, in minuti interi', () => {
    const adesso = new Date('2026-06-02T09:00:30Z');
    expect(traQuanto('2026-06-02T09:15:00Z', adesso)).toBe('tra 14 minuti');
    expect(traQuanto('2026-06-02T09:02:00Z', adesso)).toBe('tra 1 minuto');
    expect(traQuanto('2026-06-02T09:01:00Z', adesso)).toBe('tra meno di un minuto');
    expect(traQuanto('2026-06-02T08:59:00Z', adesso)).toBe('tra meno di un minuto');
  });
});
