/* Piccoli pezzi del back-office: etichette di stato, ricette (al posto delle tabelle), avvisi di arrivo. */
import { useState, type ReactNode } from 'react';
import { useLocation } from 'react-router';

import type { Dose, EsitoProva, StatoFormulazione } from '../api/ricette';
import { maiuscola, numero } from '../utilita/numeri';
import { normalizzaBotanica } from '../utilita/regole';
import { Avviso } from './Avviso';

export const NOMI_STATI: Record<StatoFormulazione, string> = {
  IN_PROVA: 'In prova',
  SCELTA: 'Scelta dal panel',
  USATA: 'Usata',
};

export const NOMI_ESITI: Record<EsitoProva, string> = {
  IN_ASSAGGIO: 'In assaggio',
  SCELTA: 'Scelta dal panel',
  ARCHIVIATA: 'Archiviata',
};

export function EtichettaStato({ stato }: { stato: StatoFormulazione }) {
  return <span className={`stato stato--${stato.toLowerCase()}`}>{NOMI_STATI[stato]}</span>;
}

export function EtichettaEsito({ esito }: { esito: EsitoProva }) {
  return <span className={`stato stato--${esito.toLowerCase()}`}>{NOMI_ESITI[esito]}</span>;
}

export interface VoceRicetta {
  chiave: string;
  nome: ReactNode;
  valore: ReactNode;
  /** Evidenziata (la botanica di stagione, la tiratura). */
  evidenza?: boolean;
}

/** Una lista "da ricettario": nome, puntini di guida, valore. */
export function Ricetta({ voci, className = '' }: { voci: VoceRicetta[]; className?: string }) {
  return (
    <ul className={`ricetta ${className}`}>
      {voci.map((voce) => (
        <li key={voce.chiave} className={voce.evidenza ? 'ricetta__voce ricetta__voce--evidenza' : 'ricetta__voce'}>
          <span className="ricetta__nome">{voce.nome}</span>
          <span className="ricetta__puntini" aria-hidden="true" />
          <span className="ricetta__valore">{voce.valore}</span>
        </li>
      ))}
    </ul>
  );
}

/** Le dosi di una prova come ricetta; la botanica di stagione è evidenziata. */
export function DosiRicetta({ dosi, botanicaDiStagione }: { dosi: Dose[]; botanicaDiStagione: string }) {
  const stagione = normalizzaBotanica(botanicaDiStagione);
  return (
    <Ricetta
      className="ricetta--dosi"
      voci={dosi.map((dose) => {
        const diStagione = normalizzaBotanica(dose.botanica) === stagione;
        return {
          chiave: dose.botanica,
          evidenza: diStagione,
          nome: (
            <>
              {maiuscola(dose.botanica)}
              {diStagione && <span className="nascosto"> (botanica di stagione)</span>}
            </>
          ),
          valore: `${numero(dose.grammiPerBottiglia)} g`,
        };
      })}
    />
  );
}

/**
 * Un avviso passato con la navigazione (es. "Edizione creata") e mostrato
 * dalla pagina di arrivo: naviga(percorso, { state: { avviso: '...' } }).
 */
export function AvvisoDiArrivo() {
  const posizione = useLocation();
  const testo = (posizione.state as { avviso?: string } | null)?.avviso;
  const [chiuso, setChiuso] = useState<string | undefined>(undefined);
  if (!testo || chiuso === posizione.key) {
    return null;
  }
  return (
    <div className="spazio-sotto">
      <Avviso titolo={testo} onChiudi={() => setChiuso(posizione.key)} />
    </div>
  );
}
