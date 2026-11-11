import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  avvisoAnnullamento,
  avvisoConferma,
  avvisoRimborso,
  avvisoTiratura,
  elencoNumeri,
  euro,
} from '../../src/notifiche/testi';

const EDIZIONE = 'e0000000-0000-4000-8000-000000000001';
const PRENOTAZIONE = 'a0000000-0000-4000-8000-000000000001';

/** Intl scrive "90,00 €" con lo spazio unificatore (U+00A0): nei confronti va bene uno spazio. */
const spazi = (testo: string) => testo.replace(/ /g, ' ');

describe('I testi degli avvisi', () => {
  it('elenca i numeri di bottiglia', () => {
    assert.equal(elencoNumeri([7]), 'la 7');
    assert.equal(elencoNumeri([112, 113]), 'la 112 e la 113');
    assert.equal(elencoNumeri([5, 6, 7]), 'la 5, la 6 e la 7');
  });

  it('scrive gli euro all\'italiana', () => {
    assert.equal(spazi(euro(90)), '90,00 €');
    assert.equal(spazi(euro(1234.5)), '1234,50 €');
  });

  it('la conferma dice importo, edizione e numeri di Giulia', () => {
    const avviso = avvisoConferma(
      {
        tipo: 'PrenotazioneConfermata',
        prenotazioneId: PRENOTAZIONE,
        edizioneId: EDIZIONE,
        acquirente: 'github:1234567',
        canale: 'SOCI',
        bottiglie: 2,
        numeriBottiglia: [112, 113],
        totale: 90,
      },
      'lavanda',
    );
    assert.equal(avviso.tipo, 'PRENOTAZIONE_CONFERMATA');
    assert.equal(avviso.titolo, 'Prenotazione confermata');
    assert.equal(
      spazi(avviso.testo),
      "Pagamento riuscito: 90,00 € per 2 bottiglie dell'edizione con lavanda. " +
        'Le tue bottiglie sono la 112 e la 113.',
    );
    assert.deepEqual(avviso.dati.numeriBottiglia, [112, 113]);
  });

  it('la tiratura ridotta rassicura chi ha già i suoi numeri', () => {
    const evento = {
      tipo: 'TiraturaRidotta' as const,
      edizioneId: EDIZIONE,
      tiraturaPrima: 600,
      tiratura: 540,
      causa: 'consegna ridotta: 10,8 su 12 kg',
    };
    const giulia = avvisoTiratura(evento, [112, 113], 'lavanda');
    assert.equal(giulia.tipo, 'TIRATURA_RIDOTTA');
    assert.equal(giulia.titolo, 'La tiratura scende a 540 bottiglie');
    assert.equal(
      giulia.testo,
      "L'edizione con lavanda passa da 600 a 540 bottiglie (consegna ridotta: 10,8 su 12 kg). " +
        'Le tue bottiglie, la 112 e la 113, restano tue.',
    );
    assert.deepEqual(giulia.dati, {
      tiraturaPrima: 600,
      tiratura: 540,
      numeriBottiglia: [112, 113],
      causa: 'consegna ridotta: 10,8 su 12 kg',
    });
    const una = avvisoTiratura(evento, [7], 'lavanda');
    assert.match(una.testo, /La tua bottiglia, la 7, resta tua\.$/);
  });

  it('la tiratura aumentata e un\'edizione sconosciuta', () => {
    const avviso = avvisoTiratura(
      { tipo: 'TiraturaAumentata', edizioneId: EDIZIONE, tiraturaPrima: 540, tiratura: 600 },
      [112, 113],
      undefined,
    );
    assert.equal(avviso.tipo, 'TIRATURA_AUMENTATA');
    assert.equal(avviso.titolo, 'La tiratura sale a 600 bottiglie');
    assert.equal(
      avviso.testo,
      "L'edizione passa da 540 a 600 bottiglie. Le tue bottiglie restano la 112 e la 113.",
    );
  });

  it("l'annullamento usa la spiegazione di Edizioni", () => {
    const base = {
      tipo: 'PrenotazioneAnnullata' as const,
      prenotazioneId: PRENOTAZIONE,
      edizioneId: EDIZIONE,
      acquirente: 'github:1234567',
      canale: 'SOCI',
      bottiglie: 2,
    };
    const rifiutata = avvisoAnnullamento(
      { ...base, causa: 'PAGAMENTO_RIFIUTATO', spiegazione: 'La banca ha rifiutato la carta.' },
      'lavanda',
    );
    assert.equal(rifiutata.titolo, 'Pagamento non riuscito');
    assert.equal(
      rifiutata.testo,
      "La prenotazione di 2 bottiglie dell'edizione con lavanda è annullata. " +
        'La banca ha rifiutato la carta.',
    );
    const scaduta = avvisoAnnullamento({ ...base, bottiglie: 1, causa: 'SCADUTA' }, 'lavanda');
    assert.equal(scaduta.titolo, 'Prenotazione scaduta');
    assert.equal(
      scaduta.testo,
      "La prenotazione di 1 bottiglia dell'edizione con lavanda è annullata. " +
        'Il tempo per pagare è finito: le bottiglie sono tornate disponibili.',
    );
  });

  it('il rimborso converte i centesimi', () => {
    const avviso = avvisoRimborso(
      {
        tipo: 'RimborsoEseguito',
        prenotazioneId: PRENOTAZIONE,
        edizioneId: EDIZIONE,
        acquirente: 'github:1234567',
        importoCentesimi: 9000,
        valuta: 'eur',
        motivo: 'pagamento arrivato a prenotazione scaduta',
      },
      'lavanda',
    );
    assert.equal(avviso.titolo, 'Rimborso eseguito');
    assert.equal(
      spazi(avviso.testo),
      "Ti abbiamo restituito 90,00 € per la prenotazione dell'edizione con lavanda " +
        '(pagamento arrivato a prenotazione scaduta).',
    );
  });
});
