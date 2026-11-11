import type {
  PrenotazioneAnnullata,
  PrenotazioneConfermata,
  RimborsoEseguito,
  TiraturaCambiata,
} from '../eventi/lettore-eventi';

/**
 * I testi degli avvisi, in italiano: funzioni pure, senza database, per
 * provarle una per una (test/unita/testi.spec.ts).
 */
export type TipoNotifica =
  | 'PRENOTAZIONE_CONFERMATA'
  | 'PRENOTAZIONE_ANNULLATA'
  | 'TIRATURA_RIDOTTA'
  | 'TIRATURA_AUMENTATA'
  | 'RIMBORSO_ESEGUITO';

export interface Avviso {
  tipo: TipoNotifica;
  titolo: string;
  testo: string;
  /** I dati per il front-end (numeri, tirature...), salvati in jsonb. */
  dati: Record<string, unknown>;
}

/** "edizione con lavanda", o solo "edizione" se Notifiche non conosce l'edizione. */
export function edizione(botanica: string | undefined): string {
  return botanica ? `edizione con ${botanica}` : 'edizione';
}

/** 1 → "1 bottiglia", 2 → "2 bottiglie". */
export function bottiglie(quante: number): string {
  return quante === 1 ? '1 bottiglia' : `${quante} bottiglie`;
}

/** [112, 113] → "la 112 e la 113"; [5, 6, 7] → "la 5, la 6 e la 7". */
export function elencoNumeri(numeri: number[]): string {
  const articoli = numeri.map((n) => `la ${n}`);
  if (articoli.length <= 1) {
    return articoli.join('');
  }
  return `${articoli.slice(0, -1).join(', ')} e ${articoli[articoli.length - 1]}`;
}

/** 90 → "90,00 €" (con lo spazio unificatore prima di €, come scrive Intl). */
export function euro(valore: number, valuta = 'EUR'): string {
  return new Intl.NumberFormat('it-IT', { style: 'currency', currency: valuta.toUpperCase() }).format(
    valore,
  );
}

export function avvisoConferma(evento: PrenotazioneConfermata, botanica?: string): Avviso {
  const numeri = elencoNumeri(evento.numeriBottiglia);
  const tue =
    evento.numeriBottiglia.length === 1
      ? `La tua bottiglia è ${numeri}.`
      : `Le tue bottiglie sono ${numeri}.`;
  return {
    tipo: 'PRENOTAZIONE_CONFERMATA',
    titolo: 'Prenotazione confermata',
    testo:
      `Pagamento riuscito: ${euro(evento.totale)} per ${bottiglie(evento.bottiglie)} ` +
      `dell'${edizione(botanica)}. ${tue}`,
    dati: {
      canale: evento.canale,
      bottiglie: evento.bottiglie,
      numeriBottiglia: evento.numeriBottiglia,
      totale: evento.totale,
    },
  };
}

export function avvisoAnnullamento(evento: PrenotazioneAnnullata, botanica?: string): Avviso {
  const titoli: Record<string, string> = {
    PAGAMENTO_RIFIUTATO: 'Pagamento non riuscito',
    SCADUTA: 'Prenotazione scaduta',
  };
  const spiegazioni: Record<string, string> = {
    PAGAMENTO_RIFIUTATO: 'Il pagamento non è andato a buon fine.',
    SCADUTA: 'Il tempo per pagare è finito: le bottiglie sono tornate disponibili.',
  };
  const spiegazione =
    evento.spiegazione ?? spiegazioni[evento.causa] ?? 'Le bottiglie sono tornate disponibili.';
  return {
    tipo: 'PRENOTAZIONE_ANNULLATA',
    titolo: titoli[evento.causa] ?? 'Prenotazione annullata',
    testo:
      `La prenotazione di ${bottiglie(evento.bottiglie)} dell'${edizione(botanica)} ` +
      `è annullata. ${spiegazione}`,
    dati: { canale: evento.canale, bottiglie: evento.bottiglie, causa: evento.causa },
  };
}

/**
 * TiraturaRidotta o TiraturaAumentata per chi ha bottiglie CONFERMATE
 * dell'edizione: i suoi numeri, anche di più prenotazioni, in ordine.
 */
export function avvisoTiratura(
  evento: TiraturaCambiata,
  numeri: number[],
  botanica?: string,
): Avviso {
  const ridotta = evento.tipo === 'TiraturaRidotta';
  const elenco = elencoNumeri(numeri);
  const una = numeri.length === 1;
  let tue: string;
  if (ridotta) {
    tue = una
      ? `La tua bottiglia, ${elenco}, resta tua.`
      : `Le tue bottiglie, ${elenco}, restano tue.`;
  } else {
    tue = una ? `La tua bottiglia resta ${elenco}.` : `Le tue bottiglie restano ${elenco}.`;
  }
  const causa = evento.causa ? ` (${evento.causa})` : '';
  return {
    tipo: ridotta ? 'TIRATURA_RIDOTTA' : 'TIRATURA_AUMENTATA',
    titolo: `La tiratura ${ridotta ? 'scende' : 'sale'} a ${evento.tiratura} bottiglie`,
    testo:
      `L'${edizione(botanica)} passa da ${evento.tiraturaPrima} a ${evento.tiratura} ` +
      `bottiglie${causa}. ${tue}`,
    dati: {
      tiraturaPrima: evento.tiraturaPrima,
      tiratura: evento.tiratura,
      numeriBottiglia: numeri,
      causa: evento.causa ?? null,
    },
  };
}

export function avvisoRimborso(evento: RimborsoEseguito, botanica?: string): Avviso {
  const importo = euro(evento.importoCentesimi / 100, evento.valuta);
  const motivo = evento.motivo ? ` (${evento.motivo})` : '';
  return {
    tipo: 'RIMBORSO_ESEGUITO',
    titolo: 'Rimborso eseguito',
    testo: `Ti abbiamo restituito ${importo} per la prenotazione dell'${edizione(botanica)}${motivo}.`,
    dati: {
      importoCentesimi: evento.importoCentesimi,
      valuta: evento.valuta,
      motivo: evento.motivo ?? null,
    },
  };
}
