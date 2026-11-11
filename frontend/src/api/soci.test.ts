import { describe, expect, it } from 'vitest';

import type { Edizione } from './edizioni';
import {
  carta,
  estiva,
  fase,
  nomeEdizione,
  numeriBottiglie,
  possibilita,
  type Prenotazione,
  type RegoleDiVendita,
} from './soci';

const REGOLE: RegoleDiVendita = {
  giorniDiPrelazione: 7,
  bottigliePerSocio: 2,
  bottigliePerCliente: 6,
  prezzoSoci: 45,
  prezzoOnline: 52,
  minutiPerPagare: 15,
  metodiDiPagamento: ['pm_card_visa', 'pm_card_chargeDeclined'],
};

type PerPossibilita = Pick<Edizione, 'id' | 'stato' | 'quote' | 'prenotate'>;

function edizione(modifiche: Partial<PerPossibilita> = {}): PerPossibilita {
  return {
    id: 'e1',
    stato: 'IN_PRELAZIONE',
    quote: { SOCI: 300, BAR: 120, ONLINE: 120 },
    prenotate: { SOCI: 0, BAR: 0, ONLINE: 0 },
    ...modifiche,
  };
}

function prenotazione(modifiche: Partial<Prenotazione> = {}): Prenotazione {
  return {
    id: 'p1',
    edizione: {
      id: 'e1',
      botanicaDiStagione: 'lavanda',
      origineBotanica: 'Alta Val Varaita (Alpi Cozie)',
      tiratura: 540,
      creataIl: '2026-06-01T10:00:00Z',
    },
    canale: 'SOCI',
    bottiglie: 2,
    prezzoPerBottiglia: 45,
    totale: 90,
    stato: 'IN_ATTESA_PAGAMENTO',
    prenotataIl: '2026-06-02T09:00:00Z',
    scadeIl: '2026-06-02T09:15:00Z',
    pagamento: null,
    numeriBottiglia: [],
    annullamento: null,
    rimborso: null,
    ritiro: {
      occasione: 'masterclass d’agosto',
      tema: 'Il ginepro e la lavanda',
      descrizione: 'In distilleria.',
      inizio: '2026-08-22T18:00:00+02:00',
      fine: '2026-08-22T20:00:00+02:00',
      luogo: 'Distilleria GinVault',
      daPortare: 'Un documento',
    },
    ...modifiche,
  };
}

describe('a che punto è una prenotazione (fase)', () => {
  const prima = new Date('2026-06-02T09:10:00Z');
  const dopo = new Date('2026-06-02T09:15:00Z');

  it('da pagare finché non scade, poi tempo finito (la annulla Edizioni)', () => {
    expect(fase(prenotazione(), prima)).toBe('DA_PAGARE');
    expect(fase(prenotazione(), dopo)).toBe('TEMPO_FINITO');
  });

  it('in pagamento appena parte la richiesta, anche oltre la scadenza: decide la SAGA', () => {
    const pagamento = { metodo: 'pm_card_visa', richiestoIl: '2026-06-02T09:14:00Z', riferimento: null, confermataIl: null };
    expect(fase(prenotazione({ pagamento }), prima)).toBe('IN_PAGAMENTO');
    expect(fase(prenotazione({ pagamento }), dopo)).toBe('IN_PAGAMENTO');
  });

  it('confermata e annullata restano tali', () => {
    expect(fase(prenotazione({ stato: 'CONFERMATA', numeriBottiglia: [112, 113] }), dopo)).toBe('CONFERMATA');
    expect(fase(prenotazione({ stato: 'ANNULLATA' }), prima)).toBe('ANNULLATA');
  });
});

describe('quante bottiglie si possono ancora prenotare (possibilita)', () => {
  it('in prelazione: quota dei soci, al massimo 2, a 45 €', () => {
    expect(possibilita(edizione(), REGOLE, [])).toEqual({
      canale: 'SOCI',
      prezzo: 45,
      massimo: 2,
      giaPrenotate: 0,
      libere: 300,
      ancora: 2,
      motivo: null,
    });
  });

  it('dopo la prelazione: quota online, al massimo 6, a 52 €', () => {
    const online = possibilita(edizione({ stato: 'IN_VENDITA' }), REGOLE, []);
    expect(online).toMatchObject({ canale: 'ONLINE', prezzo: 52, massimo: 6, ancora: 6 });
  });

  it('il limite conta le prenotazioni non annullate dello stesso canale e della stessa edizione', () => {
    const mie = [
      prenotazione({ id: 'a', bottiglie: 1, stato: 'CONFERMATA' }),
      prenotazione({ id: 'b', bottiglie: 2, stato: 'ANNULLATA' }),
      prenotazione({ id: 'c', bottiglie: 2, edizione: { ...prenotazione().edizione, id: 'altra' } }),
      prenotazione({ id: 'd', bottiglie: 3, canale: 'ONLINE' }),
    ];
    expect(possibilita(edizione(), REGOLE, mie)).toMatchObject({ giaPrenotate: 1, ancora: 1, motivo: null });
    expect(possibilita(edizione({ stato: 'IN_VENDITA' }), REGOLE, mie)).toMatchObject({ giaPrenotate: 3, ancora: 3 });
  });

  it('raggiunto il massimo: LIMITE', () => {
    const mie = [prenotazione({ bottiglie: 2, stato: 'CONFERMATA' })];
    expect(possibilita(edizione(), REGOLE, mie)).toMatchObject({ ancora: 0, motivo: 'LIMITE' });
  });

  it('non più delle bottiglie libere nella quota; a quota finita: ESAURITA', () => {
    const quasi = edizione({ prenotate: { SOCI: 299, BAR: 0, ONLINE: 0 } });
    expect(possibilita(quasi, REGOLE, [])).toMatchObject({ libere: 1, ancora: 1 });
    const finita = edizione({ stato: 'IN_VENDITA', prenotate: { SOCI: 300, BAR: 0, ONLINE: 120 } });
    expect(possibilita(finita, REGOLE, [])).toMatchObject({ libere: 0, ancora: 0, motivo: 'ESAURITA' });
  });
});

describe('parole dell’area soci', () => {
  it('i numeri delle bottiglie', () => {
    expect(numeriBottiglie([])).toBe('');
    expect(numeriBottiglie([112])).toBe('n. 112');
    expect(numeriBottiglie([112, 113])).toBe('n. 112 e 113');
    expect(numeriBottiglie([110, 111, 112])).toBe('n. 110, 111 e 112');
  });

  it('le carte di prova del menu, e un nome comunque per le altre', () => {
    expect(carta('pm_card_visa').nome).toBe('Visa di prova');
    expect(carta('pm_card_chargeDeclined').nome).toBe('Carta rifiutata');
    expect(carta('pm_card_mastercard')).toEqual({ nome: 'pm_card_mastercard', aiuto: 'Carta di prova di Stripe.' });
  });

  it('il nome dell’edizione dalla stagione dell’annuncio, la lavanda solo d’estate', () => {
    expect(nomeEdizione({ creataIl: '2026-06-01T10:00:00Z' })).toBe('Edizione d’estate 2026');
    expect(estiva('Edizione d’estate 2026')).toBe(true);
    expect(estiva(nomeEdizione({ creataIl: '2026-11-11T10:00:00Z' }))).toBe(false);
  });
});
