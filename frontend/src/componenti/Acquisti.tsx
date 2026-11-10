/* Pezzi dell'area acquisti: le schede in cima, le etichette degli ordini, la misura dei kg, l'avviso della consegna ridotta. */
import { Link, useLocation } from 'react-router';

import type { Consegna, EsitoQualita, Lotto, Ordine } from '../api/magazzino';
import { NOMI_ESITI_QUALITA } from '../api/magazzino';
import { PAGINE } from '../navigazione';
import { giorni, giorniTra, kg, numero, oggi } from '../utilita/numeri';
import { testoConsegnaRidotta } from '../utilita/regole';
import { Avviso } from './Avviso';

export const PERCORSI_ACQUISTI = {
  ordini: PAGINE.acquisti.percorso,
  nuovoOrdine: `${PAGINE.acquisti.percorso}/ordini/nuovo`,
  ordine: (id: string) => `${PAGINE.acquisti.percorso}/ordini/${id}`,
  lotti: `${PAGINE.acquisti.percorso}/lotti`,
  lotto: (id: string) => `${PAGINE.acquisti.percorso}/lotti/${id}`,
  fornitori: `${PAGINE.acquisti.percorso}/fornitori`,
  fornitore: (id: string) => `${PAGINE.acquisti.percorso}/fornitori/${id}`,
};

const SCHEDE = [
  { a: PERCORSI_ACQUISTI.ordini, testo: 'Ordini', sotto: `${PAGINE.acquisti.percorso}/ordini/` },
  { a: PERCORSI_ACQUISTI.lotti, testo: 'Lotti', sotto: `${PERCORSI_ACQUISTI.lotti}/` },
  { a: PERCORSI_ACQUISTI.fornitori, testo: 'Fornitori', sotto: `${PERCORSI_ACQUISTI.fornitori}/` },
];

/** La scheda della pagina aperta: /acquisti e /acquisti/ordini/... sono gli ordini. */
export function schedaAttiva(percorso: string): string | undefined {
  const pulito = percorso.replace(/\/+$/, '') || '/';
  return SCHEDE.find((scheda) => pulito === scheda.a || pulito.startsWith(scheda.sotto))?.a;
}

/** Ordini, lotti, fornitori: in fondo alla fascia delle pagine dell'area acquisti. */
export function SchedeAcquisti() {
  const attiva = schedaAttiva(useLocation().pathname);
  return (
    <nav className="filtri" aria-label="Area acquisti">
      {SCHEDE.map((scheda) => (
        <Link
          key={scheda.a}
          to={scheda.a}
          className="filtri__voce"
          aria-current={scheda.a === attiva ? 'page' : undefined}
        >
          {scheda.testo}
        </Link>
      ))}
    </nav>
  );
}

/** Le etichette di un ordine: da ricevere (o in attesa oltre la data), consegnato, ridotto, in ritardo. */
export function EtichetteOrdine({ ordine, oggiARoma = oggi() }: { ordine: Ordine; oggiARoma?: string }) {
  const consegna = ordine.consegna;
  if (!consegna) {
    const oltre = giorniTra(ordine.consegnaAttesa, oggiARoma);
    return (
      <span className="etichette">
        <span className="stato stato--aperto">Da ricevere</span>
        {oltre > 0 && <span className="stato stato--ritardo">Atteso da {giorni(oltre)}</span>}
      </span>
    );
  }
  return (
    <span className="etichette">
      <span className="stato stato--consegnato">Consegnato</span>
      {consegna.ridotta && <span className="stato stato--ridotta">Ridotta</span>}
      {consegna.inRitardo && <span className="stato stato--ritardo">In ritardo di {giorni(consegna.giorniDiRitardo)}</span>}
    </span>
  );
}

/** Conforme / non conforme, e se il lotto si può ancora usare. */
export function EtichetteLotto({ lotto }: { lotto: Pick<Lotto, 'esitoQualita' | 'utilizzabile'> }) {
  return (
    <span className="etichette">
      <EtichettaQualita esito={lotto.esitoQualita} />
      {lotto.esitoQualita === 'CONFORME' && !lotto.utilizzabile && <span className="stato stato--ritardo">Scaduto</span>}
    </span>
  );
}

export function EtichettaQualita({ esito }: { esito: EsitoQualita }) {
  return <span className={`stato stato--${esito.toLowerCase()}`}>{NOMI_ESITI_QUALITA[esito]}</span>;
}

export interface ParteDellaMisura {
  chiave: string;
  kg: number;
  /** pieno: i kg che ci sono; integrato: da un lotto integrato; atteso: contati ma non ancora arrivati. */
  tipo: 'pieno' | 'integrato' | 'atteso';
  etichetta: string;
}

/**
 * Una barra di kg: le parti piene da sinistra, e quello che manca al totale
 * tratteggiato. Per la consegna di Elena: 10,8 kg pieni su 12, 1,2 tratteggiati.
 * All'arrivo le parti si riempiono una volta (non con prefers-reduced-motion).
 */
export function MisuraKg({ totale, parti, mancanti }: { totale: number; parti: ParteDellaMisura[]; mancanti: string }) {
  const somma = parti.reduce((s, parte) => s + Math.round(parte.kg * 1000), 0) / 1000;
  const vuoto = Math.max(0, Math.round((totale - somma) * 1000) / 1000);
  const percento = (valore: number) => (totale > 0 ? `${Math.min(100, (valore / totale) * 100)}%` : '0%');
  const descrizione = [
    ...parti.map((parte) => `${kg(parte.kg)} ${parte.etichetta}`),
    vuoto > 0 ? `${kg(vuoto)} ${mancanti}` : undefined,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <div className="misura">
      <div className="misura__barra" role="img" aria-label={`${descrizione}, su ${kg(totale)}`}>
        {parti.map((parte, indice) => (
          <span
            key={parte.chiave}
            className={`misura__parte misura__parte--${parte.tipo}`}
            style={{ width: percento(parte.kg), animationDelay: `${indice * 180}ms` }}
          />
        ))}
      </div>
      <p className="misura__legenda" aria-hidden="true">
        {parti.map((parte) => (
          <span key={parte.chiave} className={`misura__voce misura__voce--${parte.tipo}`}>
            {kg(parte.kg)} {parte.etichetta}
          </span>
        ))}
        {vuoto > 0 && (
          <span className="misura__voce misura__voce--vuoto">
            {kg(vuoto)} {mancanti}
          </span>
        )}
        <span className="misura__totale">su {kg(totale)}</span>
      </p>
    </div>
  );
}

/** La misura di una consegna: arrivati su ordinati. */
export function MisuraConsegna({ ordine }: { ordine: Ordine }) {
  const consegna = ordine.consegna;
  return (
    <MisuraKg
      totale={ordine.kgOrdinati}
      parti={consegna ? [{ chiave: 'arrivati', kg: consegna.kgArrivati, tipo: 'pieno', etichetta: 'arrivati' }] : []}
      mancanti={consegna ? 'non arriveranno' : 'da ricevere'}
    />
  );
}

/** "Consegna ridotta: 10,8 su 12 kg", con cosa vuol dire per l'ordine e per l'edizione. */
export function AvvisoConsegnaRidotta({ consegna, kgOrdinati }: { consegna: Consegna; kgOrdinati: number }) {
  return (
    <Avviso tipo="attenzione" titolo={testoConsegnaRidotta(consegna.kgArrivati, kgOrdinati)}>
      <p>
        Mancano {kg(consegna.kgMancanti)}. L’ordine è chiuso: il resto non arriverà. Produzione ricalcola da sola i kg
        del piano, ed Edizioni la tiratura: con {numero(consegna.kgArrivati)} kg le bottiglie possono scendere.
      </p>
    </Avviso>
  );
}
