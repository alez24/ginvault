import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { EventoNonValido, leggiBusta, leggiEvento } from '../../src/eventi/lettore-eventi';

const EDIZIONE = 'e0000000-0000-4000-8000-000000000001';
const PRENOTAZIONE = 'a0000000-0000-4000-8000-000000000001';

function messaggio(tipo: string, payload: Record<string, unknown>, extra: object = {}): string {
  return JSON.stringify({
    eventId: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d',
    tipo,
    versione: 1,
    occorsoIl: '2026-08-01T09:05:03Z',
    payload,
    ...extra,
  });
}

const conferma = {
  prenotazioneId: PRENOTAZIONE,
  edizioneId: EDIZIONE,
  acquirente: 'github:1234567',
  canale: 'SOCI',
  bottiglie: 2,
  numeriBottiglia: [112, 113],
  totale: 90.0,
  pagamentoId: 'c0000000-0000-4000-8000-000000000001',
};

describe('Il lettore degli eventi', () => {
  it('legge PrenotazioneConfermata come in docs/eventi.md, ignorando i campi in più', () => {
    const busta = leggiBusta(Buffer.from(messaggio('PrenotazioneConfermata', conferma)));
    assert.equal(busta.occorsoIl.toISOString(), '2026-08-01T09:05:03.000Z');
    assert.deepEqual(leggiEvento(busta), {
      tipo: 'PrenotazioneConfermata',
      prenotazioneId: PRENOTAZIONE,
      edizioneId: EDIZIONE,
      acquirente: 'github:1234567',
      canale: 'SOCI',
      bottiglie: 2,
      numeriBottiglia: [112, 113],
      totale: 90,
    });
  });

  it('un tipo che non interessa a Notifiche è null', () => {
    const busta = leggiBusta(messaggio('RicalcoloRifiutato', { edizioneId: EDIZIONE }));
    assert.equal(leggiEvento(busta), null);
    // anche con una versione che Notifiche non conosce: non lo legge comunque
    const futura = leggiBusta(messaggio('PagamentoRiuscito', {}, { versione: 7 }));
    assert.equal(leggiEvento(futura), null);
  });

  it('i messaggi illeggibili sono EventoNonValido', () => {
    const casi: [string, string | null][] = [
      ['vuoto', null],
      ['JSON rotto', '{"eventId": '],
      ['una lista', '[1, 2]'],
      ['senza eventId', JSON.stringify({ tipo: 'EdizioneCreata', occorsoIl: '2026-08-01T09:00:00Z', payload: {} })],
      ['data sbagliata', messaggio('EdizioneCreata', {}, { occorsoIl: 'ieri' })],
      ['senza payload', messaggio('EdizioneCreata', {}, { payload: null })],
    ];
    for (const [caso, valore] of casi) {
      assert.throws(() => leggiBusta(valore), EventoNonValido, caso);
    }
  });

  it('un evento letto da Notifiche con un campo sbagliato è EventoNonValido', () => {
    const casi: [string, string, Record<string, unknown>, object?][] = [
      ['versione 2', 'TiraturaRidotta', { edizioneId: EDIZIONE, tiraturaPrima: 600, tiratura: 540 }, { versione: 2 }],
      ['edizione non UUID', 'EdizioneCreata', { edizioneId: 'e1', botanicaDiStagione: 'lavanda' }],
      ['numeri che non tornano', 'PrenotazioneConfermata', { ...conferma, numeriBottiglia: [112] }],
      ['numero 0', 'PrenotazioneConfermata', { ...conferma, numeriBottiglia: [0, 1] }],
      ['totale negativo', 'PrenotazioneConfermata', { ...conferma, totale: -1 }],
      ['importo decimale', 'RimborsoEseguito', { prenotazioneId: PRENOTAZIONE, edizioneId: EDIZIONE, acquirente: 'x', importoCentesimi: 90.5, valuta: 'eur' }],
      ['senza causa', 'PrenotazioneAnnullata', { prenotazioneId: PRENOTAZIONE, edizioneId: EDIZIONE, acquirente: 'x', canale: 'SOCI', bottiglie: 2 }],
    ];
    for (const [caso, tipo, payload, extra] of casi) {
      assert.throws(() => leggiEvento(leggiBusta(messaggio(tipo, payload, extra))), EventoNonValido, caso);
    }
  });
});
