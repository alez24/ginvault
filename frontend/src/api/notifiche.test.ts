import { describe, expect, it } from 'vitest';

import {
  attesaRicollegamento,
  AVVISI_VUOTI,
  conElenco,
  conLetta,
  conNotifica,
  conTutteLette,
  indirizzoAvvisi,
  leggiMessaggio,
  tonoAvviso,
  type Notifica,
} from './notifiche';

function notifica(id: string, creataIl: string, modifiche: Partial<Notifica> = {}): Notifica {
  return {
    id,
    tipo: 'PRENOTAZIONE_CONFERMATA',
    titolo: 'Prenotazione confermata',
    testo: 'Le tue bottiglie sono la 112 e la 113.',
    edizioneId: 'e1',
    prenotazioneId: 'p1',
    dati: { numeriBottiglia: [112, 113] },
    creataIl,
    lettaIl: null,
    ...modifiche,
  };
}

describe('i messaggi del WebSocket (lettore tollerante)', () => {
  it('connesso, notifica e ping', () => {
    expect(leggiMessaggio('{"tipo":"connesso","nonLette":2}')).toEqual({ tipo: 'connesso', nonLette: 2 });
    expect(leggiMessaggio('{"tipo":"ping"}')).toEqual({ tipo: 'ping' });
    const n = notifica('n1', '2026-06-02T09:11:00Z');
    expect(leggiMessaggio(JSON.stringify({ tipo: 'notifica', notifica: n, campoNuovo: 1 }))).toEqual({
      tipo: 'notifica',
      notifica: n,
    });
  });

  it('ignora quello che non capisce', () => {
    expect(leggiMessaggio('non è json')).toBeNull();
    expect(leggiMessaggio('null')).toBeNull();
    expect(leggiMessaggio('{"tipo":"sconosciuto"}')).toBeNull();
    expect(leggiMessaggio('{"tipo":"connesso"}')).toBeNull();
    expect(leggiMessaggio('{"tipo":"notifica","notifica":{"id":"n1"}}')).toBeNull();
  });
});

describe('quando riaprire il WebSocket', () => {
  it('mai senza sessione (4401), subito con il JWT scaduto (4001)', () => {
    expect(attesaRicollegamento(4401, 0)).toBeNull();
    expect(attesaRicollegamento(4001, 3)).toBe(0);
  });

  it('per il resto sempre più di rado, fino a ogni 30 secondi', () => {
    expect([0, 1, 2, 3, 4, 9].map((tentativo) => attesaRicollegamento(1006, tentativo))).toEqual([
      1_000, 2_000, 5_000, 10_000, 30_000, 30_000,
    ]);
    expect(attesaRicollegamento(1001, 0)).toBe(1_000);
  });

  it('lo stesso host della pagina, wss in https', () => {
    expect(indirizzoAvvisi({ protocol: 'http:', host: 'localhost' })).toBe('ws://localhost/api/notifiche/avvisi');
    expect(indirizzoAvvisi({ protocol: 'https:', host: 'ginvault.example:8443' })).toBe(
      'wss://ginvault.example:8443/api/notifiche/avvisi',
    );
  });
});

describe('gli avvisi nel browser', () => {
  const vecchia = notifica('n1', '2026-06-02T09:00:00Z');
  const nuova = notifica('n2', '2026-06-02T09:11:00Z');

  it('una notifica in diretta si aggiunge una volta sola, in cima', () => {
    const una = conNotifica(conNotifica(AVVISI_VUOTI, vecchia), nuova);
    expect(una.notifiche.map((n) => n.id)).toEqual(['n2', 'n1']);
    expect(una.nonLette).toBe(2);
    expect(conNotifica(una, nuova)).toBe(una);
  });

  it('l’elenco riletto vince, ma tiene le notifiche arrivate dopo la lettura', () => {
    const inDiretta = conNotifica(AVVISI_VUOTI, nuova);
    const letta = { ...vecchia, lettaIl: '2026-06-02T09:05:00Z' };
    const stato = conElenco(inDiretta, { nonLette: 0, notifiche: [letta] });
    expect(stato.notifiche.map((n) => n.id)).toEqual(['n2', 'n1']);
    expect(stato.nonLette).toBe(1);

    // Quando il server la conosce, conta la sua versione
    const dopo = conElenco(stato, { nonLette: 1, notifiche: [nuova, letta] });
    expect(dopo.nonLette).toBe(1);
  });

  it('letta e tutte lette', () => {
    const stato = conNotifica(conNotifica(AVVISI_VUOTI, vecchia), nuova);
    const una = conLetta(stato, 'n1', '2026-06-02T09:12:00Z');
    expect(una.nonLette).toBe(1);
    expect(una.notifiche.find((n) => n.id === 'n1')?.lettaIl).toBe('2026-06-02T09:12:00Z');
    expect(conLetta(una, 'n1', '2026-06-02T09:13:00Z')).toBe(una);
    expect(conLetta(una, 'mai-vista', '2026-06-02T09:13:00Z')).toBe(una);

    const tutte = conTutteLette(una, '2026-06-02T09:14:00Z');
    expect(tutte.nonLette).toBe(0);
    expect(tutte.notifiche.map((n) => n.lettaIl)).toEqual(['2026-06-02T09:14:00Z', '2026-06-02T09:12:00Z']);
  });

  it('la tiratura ridotta e gli annullamenti si notano di più', () => {
    expect(tonoAvviso('TIRATURA_RIDOTTA')).toBe('attenzione');
    expect(tonoAvviso('PRENOTAZIONE_ANNULLATA')).toBe('attenzione');
    expect(tonoAvviso('PRENOTAZIONE_CONFERMATA')).toBe('informazione');
    expect(tonoAvviso('RIMBORSO_ESEGUITO')).toBe('informazione');
  });
});
