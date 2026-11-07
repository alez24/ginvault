/*
 * Dati finti dell'area soci (storia di Giulia), finché Edizioni non avrà le
 * prenotazioni e Notifiche non manderà gli avvisi. Hanno la forma dei tipi in
 * src/api/soci.ts: per passare ai dati veri si cambia solo quel file.
 *
 * La storia, in ordine:
 * - 2 luglio: il panel sceglie la versione 2 della formulazione;
 * - 8 luglio: Edizioni annuncia l'edizione d'estate, 600 bottiglie da 12 kg di lavanda;
 *   si apre la prelazione dei soci (7 giorni);
 * - 9 luglio: Giulia prenota 2 bottiglie e paga, la prenotazione è confermata;
 * - 18–20 luglio: il raccolto dà 10,8 kg invece di 12, la tiratura scende a 540
 *   (TiraturaRidotta): calano solo le bottiglie online, la quota dei soci resta;
 * - 3–5 agosto: imbottigliamento, numeri 112 e 113, controllo qualità superato;
 * - 22 agosto: ritiro alla masterclass d'agosto.
 *
 * Le risposte arrivano dopo un breve ritardo, come dalla rete: così si vedono
 * anche gli stati di caricamento.
 */
import { ErroreApi } from '../api/http';
import type { AreaSoci, EdizioneSoci, Prenotazione } from '../api/soci';

const RITARDO_MS = 250;

const ID_ESTATE = 'estate-2026';

const prenotazioneEstate: Prenotazione = {
  id: 'pren-2026-0412',
  edizione: { id: ID_ESTATE, nome: 'Edizione d’estate 2026', botanica: 'lavanda', tiratura: 540 },
  stato: 'NUMERATA',
  prenotataIl: '2026-07-09T10:24:00+02:00',
  numeri: [112, 113],
  ritiro: {
    occasione: 'masterclass d’agosto',
    tema: 'La lavanda nel gin',
    descrizione:
      'Due ore in distilleria con il mastro distillatore: dalla lavanda appena raccolta alla prova d’assaggio, fino all’edizione d’estate nel bicchiere. Alla fine ritiri le tue bottiglie.',
    inizio: '2026-08-22T18:00:00+02:00',
    fine: '2026-08-22T20:00:00+02:00',
    luogo: 'In distilleria, nella sala degli alambicchi',
    daPortare: 'Un documento d’identità: le bottiglie sono intestate a te.',
  },
};

const edizioneEstate: EdizioneSoci = {
  id: ID_ESTATE,
  nome: 'Edizione d’estate 2026',
  botanica: 'lavanda',
  descrizione:
    'Ginepro e lavanda d’alta quota. La lavanda è raccolta a luglio sulle Alpi Cozie e distillata nella stessa settimana.',
  grammiPerBottiglia: 20,
  botanicaAnnunciataKg: 12,
  botanicaDisponibileKg: 10.8,
  tiraturaAnnunciata: 600,
  tiratura: 540,
  quoteAnnunciate: { SOCI: 300, BAR: 150, ONLINE: 150 },
  quote: { SOCI: 300, BAR: 150, ONLINE: 90 },
  prenotazione: prenotazioneEstate,
  tracciabilita: [
    {
      tipo: 'FORMULAZIONE',
      titolo: 'La ricetta',
      quando: '2026-07-02T17:30:00+02:00',
      testo:
        'Il mastro distillatore ha provato due versioni della ricetta. Il panel d’assaggio ha scelto la seconda, con più lavanda e meno coriandolo.',
      dati: [
        { etichetta: 'Formulazione', valore: 'Estate in alta quota' },
        { etichetta: 'Versione', valore: '2 di 2, scelta dal panel' },
      ],
      dosi: [
        { botanica: 'ginepro', grammiPerBottiglia: 25 },
        { botanica: 'lavanda', grammiPerBottiglia: 20 },
        { botanica: 'scorza di limone', grammiPerBottiglia: 4 },
        { botanica: 'coriandolo', grammiPerBottiglia: 1.5 },
      ],
    },
    {
      tipo: 'BOTANICA',
      titolo: 'La lavanda',
      quando: '2026-07-18T08:00:00+02:00',
      testo:
        'Raccolta a mano a 1.450 metri. L’estate secca ha dato meno fiori del previsto: 10,8 kg invece di 12, abbastanza per 540 bottiglie.',
      dati: [
        { etichetta: 'Lotto', valore: 'LAV-26-07' },
        { etichetta: 'Provenienza', valore: 'Alpi Cozie, 1.450 m' },
        { etichetta: 'Consegnati', valore: '10,8 kg su 12 previsti' },
      ],
    },
    {
      tipo: 'CONTROLLO_QUALITA',
      titolo: 'Il controllo qualità',
      quando: '2026-08-05T11:00:00+02:00',
      testo:
        'Il lotto di gin è stato imbottigliato il 3 agosto. Il controllo di gradazione, limpidezza e profumo è superato: le bottiglie sono numerate e pronte.',
      dati: [
        { etichetta: 'Lotto di gin', valore: 'L-26-031' },
        { etichetta: 'Gradazione', valore: '40% vol, bottiglia da 70 cl' },
        { etichetta: 'Le tue bottiglie', valore: 'n. 112 e 113, numerate il 4 agosto' },
      ],
    },
  ],
};

const area: AreaSoci = {
  notifiche: [
    {
      id: 'not-2026-0720',
      evento: 'TiraturaRidotta',
      titolo: 'La tiratura è scesa da 600 a 540, la tua prenotazione è confermata',
      testo:
        'Il raccolto di lavanda ha dato 10,8 kg invece di 12. Le 60 bottiglie in meno escono dalla vendita online: la quota dei soci non cambia e le tue 2 bottiglie restano tue.',
      arrivataIl: '2026-07-20T09:12:00+02:00',
      idEdizione: ID_ESTATE,
    },
  ],
  prenotazioni: [prenotazioneEstate],
  club: { giorniDiPrelazione: 7, bottigliePerSocio: 2 },
};

/** Una copia dei dati dopo il ritardo: chi li usa non può modificare l'originale. */
function risposta<T>(dati: T): Promise<T> {
  return new Promise((risolvi) => setTimeout(() => risolvi(structuredClone(dati)), RITARDO_MS));
}

export const datiFinti = {
  area: () => risposta(area),
  edizione: (id: string): Promise<EdizioneSoci> =>
    id === ID_ESTATE
      ? risposta(edizioneEstate)
      : new Promise((_, rifiuta) =>
          setTimeout(
            () => rifiuta(new ErroreApi(404, 'Edizione non trovata', 'Nessuna edizione con questo indirizzo.')),
            RITARDO_MS,
          ),
        ),
};

/** Per i test: i dati così come sono, senza ritardo. */
export const DATI_FINTI = { area, edizioneEstate };
