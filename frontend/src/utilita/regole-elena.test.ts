/* Le regole dell'area acquisti e dell'integrazione di un lotto, con i dati della storia di Elena. */
import { describe, expect, it } from 'vitest';

import type { Fornitore, Ordine } from '../api/magazzino';
import type { LottoDelPiano, Piano } from '../api/produzione';
import {
  consegnaPrevista,
  controllaConsegna,
  controllaOrdine,
  integrazionePrevista,
  kgDaProporre,
  kgDelPiano,
  stessaOrigine,
  testoConsegnaRidotta,
  type TestiConsegna,
} from './regole';

const VAL_VARAITA = 'Alta Val Varaita (Alpi Cozie)';
const CAMPI = 'Campi GinVault (Saluzzo, 400 m)';

/** L'ordine di Elena: 12 kg di lavanda dall'Alta Val Varaita, attesi il 31 luglio. */
function ordine(modifiche: Partial<Ordine> = {}): Ordine {
  return {
    id: 'o1',
    fornitore: { id: 'f1', nome: 'Raccoglitori dell’Alta Val Varaita' },
    botanica: 'lavanda',
    kgOrdinati: 12,
    consegnaAttesa: '2026-07-31',
    edizioneId: 'e1',
    stato: 'APERTO',
    consegna: null,
    creatoIl: '2026-06-10T08:00:00Z',
    ...modifiche,
  };
}

/** Il piano dell'edizione d'estate dopo la consegna di Elena: 10,8 kg su 12. */
function piano(modifiche: Partial<Piano> = {}): Piano {
  return {
    id: 'p1',
    edizioneId: 'e1',
    formulazione: { id: 'f1', versione: 2 },
    botanica: 'lavanda',
    grammiPerBottiglia: 20,
    origineRichiesta: VAL_VARAITA,
    kgPrevisti: 12,
    kgDisponibili: 10.8,
    kgMancanti: 1.2,
    tiraturaPrevista: 600,
    bottiglieProducibili: 540,
    consegne: [
      {
        ordineId: 'o1',
        lottoId: 'l1',
        origine: VAL_VARAITA,
        kgOrdinati: 12,
        kgArrivati: 10.8,
        kgUtilizzabili: 10.8,
        motivo: 'consegna ridotta: 10,8 kg su 12',
        consegnataIl: '2026-08-06',
      },
    ],
    integrazioni: [],
    creatoIl: '2026-06-01T10:00:00Z',
    aggiornatoIl: '2026-08-06T09:00:00Z',
    ...modifiche,
  };
}

function lotto(modifiche: Partial<LottoDelPiano> = {}): LottoDelPiano {
  return {
    lottoId: 'l2',
    origine: VAL_VARAITA,
    kg: 3,
    kgDisponibili: 3,
    scadenza: '2027-08-31',
    fornitore: 'Raccoglitori dell’Alta Val Varaita',
    stessaOrigine: true,
    ...modifiche,
  };
}

/** Il lotto dei campi: 2 kg di lavanda di un'altra origine. */
const LOTTO_DEI_CAMPI = lotto({ lottoId: 'a1', origine: CAMPI, kg: 2, kgDisponibili: 2, fornitore: 'Campi GinVault', stessaOrigine: false });

const consegnaDiElena: TestiConsegna = {
  kg: '10,8',
  consegnataIl: '2026-08-06',
  scadenza: '2027-08-31',
  esito: 'CONFORME',
  nota: '  pioggia   durante la fioritura ',
};

describe('consegna (OrdineDiAcquisto.registraConsegna)', () => {
  it('la consegna di Elena: 10,8 kg su 12, sei giorni dopo il 31 luglio, ridotta e in ritardo', () => {
    const { consegna, errori } = controllaConsegna(consegnaDiElena, ordine(), '2026-08-06');
    expect(errori).toEqual({});
    expect(consegna).toEqual({
      kgArrivati: 10.8,
      consegnataIl: '2026-08-06',
      scadenza: '2027-08-31',
      esitoQualita: 'CONFORME',
      nota: 'pioggia durante la fioritura',
    });
    expect(consegnaPrevista(ordine(), 10.8, '2026-08-06')).toEqual({
      ridotta: true,
      kgMancanti: 1.2,
      inRitardo: true,
      giorniDiRitardo: 6,
    });
    expect(testoConsegnaRidotta(10.8, 12)).toBe('Consegna ridotta: 10,8 su 12 kg');
  });

  it('completa e puntuale: niente avviso', () => {
    expect(consegnaPrevista(ordine(), 12, '2026-07-30')).toEqual({
      ridotta: false,
      kgMancanti: 0,
      inRitardo: false,
      giorniDiRitardo: 0,
    });
  });

  it('i kg mancanti sono al grammo, senza errori di virgola mobile', () => {
    expect(consegnaPrevista(ordine({ kgOrdinati: 0.3 }), 0.1, '2026-07-31').kgMancanti).toBe(0.2);
  });

  it('non più kg di quelli ordinati: quelli in più vanno in un altro ordine', () => {
    const { consegna, errori } = controllaConsegna({ ...consegnaDiElena, kg: '12,5' }, ordine(), '2026-08-06');
    expect(consegna).toBeNull();
    expect(errori.kg).toContain('Ne erano stati ordinati 12 kg');
  });

  it('la consegna non è nel futuro, e il lotto scade dopo la consegna', () => {
    const futura = controllaConsegna({ ...consegnaDiElena, consegnataIl: '2026-08-07' }, ordine(), '2026-08-06');
    expect(futura.errori.consegnataIl).toContain('nel futuro');
    const scaduto = controllaConsegna({ ...consegnaDiElena, scadenza: '2026-08-06' }, ordine(), '2026-08-06');
    expect(scaduto.errori.scadenza).toContain('dopo il giorno della consegna');
  });

  it('servono kg, date valide e l’esito del controllo qualità; la nota è facoltativa', () => {
    const { consegna, errori } = controllaConsegna(
      { kg: '', consegnataIl: '2026-02-30', scadenza: '', esito: '', nota: '' },
      ordine(),
      '2026-08-06',
    );
    expect(consegna).toBeNull();
    expect(errori).toEqual({
      kg: 'Scrivi i kg arrivati.',
      consegnataIl: 'Non è una data valida.',
      scadenza: 'Scrivi la scadenza del lotto.',
      esito: 'Scegli l’esito del controllo qualità.',
    });
    const senzaNota = controllaConsegna({ ...consegnaDiElena, nota: '   ' }, ordine(), '2026-08-06');
    expect(senzaNota.consegna).not.toHaveProperty('nota');
  });

  it('la nota al massimo 500 caratteri', () => {
    const { errori } = controllaConsegna({ ...consegnaDiElena, nota: 'x'.repeat(501) }, ordine(), '2026-08-06');
    expect(errori.nota).toContain('500');
  });
});

describe('nuovo ordine (OrdineDiAcquisto.nuovo)', () => {
  const fornitori: Fornitore[] = [
    {
      id: 'f1',
      nome: 'Raccoglitori dell’Alta Val Varaita',
      origine: VAL_VARAITA,
      riepilogo: { consegne: 0, puntuali: 0, inRitardo: 0, ridotte: 0 },
      storico: [],
    },
  ];

  it('la botanica e l’edizione vengono dal piano scelto', () => {
    const { ordine: nuovo, errori } = controllaOrdine(
      { pianoId: 'p1', fornitoreId: 'f1', kg: '12', consegnaAttesa: '2026-07-31' },
      [piano()],
      fornitori,
    );
    expect(errori).toEqual({});
    expect(nuovo).toEqual({
      fornitoreId: 'f1',
      botanica: 'lavanda',
      kgOrdinati: 12,
      consegnaAttesa: '2026-07-31',
      edizioneId: 'e1',
    });
  });

  it('senza edizione, fornitore, kg e data non si ordina', () => {
    const { ordine: nuovo, errori } = controllaOrdine(
      { pianoId: '', fornitoreId: 'sconosciuto', kg: '0', consegnaAttesa: '' },
      [piano()],
      fornitori,
    );
    expect(nuovo).toBeNull();
    expect(Object.keys(errori).sort()).toEqual(['consegnaAttesa', 'fornitoreId', 'kg', 'pianoId']);
  });

  it('le origini si confrontano come in Produzione: contano le parole, non maiuscole e spazi', () => {
    expect(stessaOrigine(VAL_VARAITA, '  alta val  varaita (alpi cozie) ')).toBe(true);
    expect(stessaOrigine(VAL_VARAITA, CAMPI)).toBe(false);
  });
});

describe('integrazione di un lotto (PianoDiProduzione.integra)', () => {
  it('il lotto dei campi ha un’altra origine: Produzione lo rifiuterà', () => {
    expect(integrazionePrevista(piano(), LOTTO_DEI_CAMPI, 1.2)).toEqual({ esito: 'origine-diversa' });
  });

  it('un lotto dell’Alta Val Varaita copre gli 1,2 kg: 12 kg, di nuovo 600 bottiglie', () => {
    expect(integrazionePrevista(piano(), lotto(), 1.2)).toEqual({ esito: 'possibile', kgDopo: 12, bottiglie: 600 });
  });

  it('non più dei kg che mancano, né più di quelli liberi nel lotto', () => {
    expect(integrazionePrevista(piano(), lotto(), 1.5)).toEqual({ esito: 'troppi', kgMancanti: 1.2 });
    expect(integrazionePrevista(piano(), lotto({ kgDisponibili: 0.5 }), 1)).toEqual({ esito: 'non-bastano', kgLiberi: 0.5 });
  });

  it('propone i kg che mancano, o quelli liberi se sono meno', () => {
    expect(kgDaProporre(lotto(), 1.2)).toBe(1.2);
    expect(kgDaProporre(lotto({ kgDisponibili: 0.7 }), 1.2)).toBe(0.7);
  });
});

describe('da dove vengono i kg del piano', () => {
  it('dopo la consegna di Elena: 10,8 dalle consegne, 1,2 mancanti', () => {
    expect(kgDelPiano(piano())).toEqual({ consegnati: 10.8, integrati: 0, attesi: 0, mancanti: 1.2 });
  });

  it('prima di ogni consegna i 12 kg sono tutti attesi', () => {
    expect(kgDelPiano(piano({ kgDisponibili: 12, kgMancanti: 0, consegne: [] }))).toEqual({
      consegnati: 0,
      integrati: 0,
      attesi: 12,
      mancanti: 0,
    });
  });

  it('con il lotto integrato: 10,8 + 1,2', () => {
    const integrato = piano({
      kgDisponibili: 12,
      kgMancanti: 0,
      integrazioni: [{ lottoId: 'l2', origine: VAL_VARAITA, kg: 1.2, motivo: 'integrazione', integrataIl: '2026-08-07T10:00:00Z' }],
    });
    expect(kgDelPiano(integrato)).toEqual({ consegnati: 10.8, integrati: 1.2, attesi: 0, mancanti: 0 });
  });
});
