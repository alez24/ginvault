import type { ReactNode } from 'react';
import { Link } from 'react-router';

import { CRINALE_TESTATA } from './Montagne';

interface Props {
  titolo: ReactNode;
  attacco?: ReactNode;
  /** Il collegamento per tornare all'elenco, sopra il titolo. */
  ritorno?: { a: string; testo: string };
  /** Sopra il titolo, per esempio lo stato della formulazione. */
  sopra?: ReactNode;
  /** A destra del titolo: i pulsanti della pagina. */
  azioni?: ReactNode;
  /** A destra, più grande: la bottiglia dell'edizione. */
  figura?: ReactNode;
  /** In fondo alla fascia, per esempio i filtri. */
  piede?: ReactNode;
}

/**
 * La fascia avorio in cima alle pagine riservate: titolo, azioni e filtri, e in
 * fondo un crinale basso che scende sulla neve del resto della pagina.
 */
export function TestataPagina({ titolo, attacco, ritorno, sopra, azioni, figura, piede }: Props) {
  return (
    <header className={figura ? 'testata-pagina testata-pagina--figura' : 'testata-pagina'}>
      <div className="contenitore testata-pagina__griglia">
        <div className="testata-pagina__testo">
          {(ritorno || sopra) && (
            <div className="testata-pagina__sopra">
              {ritorno && (
                <Link className="ritorno" to={ritorno.a}>
                  {ritorno.testo}
                </Link>
              )}
              {sopra}
            </div>
          )}
          <h1>{titolo}</h1>
          {attacco && <p className="attacco">{attacco}</p>}
        </div>
        {azioni && <div className="testata-pagina__azioni">{azioni}</div>}
        {figura && <div className="testata-pagina__figura">{figura}</div>}
      </div>
      {piede && <div className="contenitore testata-pagina__piede">{piede}</div>}
      <svg className="testata-pagina__crinale" viewBox="0 0 1440 80" preserveAspectRatio="none" aria-hidden="true" focusable="false">
        <path className="testata-pagina__lontano" d={CRINALE_TESTATA.lontano} />
        <path className="testata-pagina__vicino" d={CRINALE_TESTATA.vicino} />
      </svg>
    </header>
  );
}

export interface Indicatore {
  chiave: string;
  etichetta: string;
  valore: ReactNode;
  unita?: string;
  /** L'indicatore principale, più largo. */
  principale?: boolean;
}

/** I numeri della pagina in schede bianche, subito sotto la fascia. */
export function Indicatori({ voci, etichetta }: { voci: Indicatore[]; etichetta: string }) {
  return (
    <div className="contenitore">
      <dl className="indicatori" aria-label={etichetta}>
        {voci.map((voce) => (
          <div key={voce.chiave} className={`indicatore${voce.principale ? ' indicatore--principale' : ''}`}>
            <dt className="indicatore__etichetta">{voce.etichetta}</dt>
            <dd className="indicatore__valore">
              {voce.valore}
              {voce.unita && <span className="indicatore__unita"> {voce.unita}</span>}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
