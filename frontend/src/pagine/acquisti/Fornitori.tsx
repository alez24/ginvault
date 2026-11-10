import { Link, useParams } from 'react-router';

import { magazzino, type Fornitore, type VoceStorico } from '../../api/magazzino';
import { PERCORSI_ACQUISTI, SchedeAcquisti } from '../../componenti/Acquisti';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { giorni, kg, numero, partiDelGiorno } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/** "3 consegne: 1 ridotta, 2 in ritardo", oppure "Nessuna consegna ancora". */
export function riassunto(fornitore: Fornitore): string {
  const { consegne, ridotte, inRitardo } = fornitore.riepilogo;
  if (consegne === 0) {
    return 'Nessuna consegna ancora.';
  }
  const quante = consegne === 1 ? '1 consegna' : `${numero(consegne)} consegne`;
  const parti = [
    ridotte > 0 ? (ridotte === 1 ? '1 ridotta' : `${numero(ridotte)} ridotte`) : undefined,
    inRitardo > 0 ? `${numero(inRitardo)} in ritardo` : undefined,
  ].filter(Boolean);
  return parti.length === 0 ? `${quante}, tutte complete e puntuali.` : `${quante}: ${parti.join(', ')}.`;
}

/** GET /api/magazzino/fornitori, in ordine alfabetico. */
export function ElencoFornitori() {
  const { risultato, ricarica } = useCaricamento(() => magazzino.fornitori(), 'fornitori');
  return (
    <section className="pagina">
      <title>Fornitori · GinVault</title>
      <TestataPagina
        titolo="Fornitori"
        attacco="L’origine di un lotto è quella del suo fornitore. Lo storico dice com’è andata ogni consegna: puntuale o no, completa o ridotta."
        piede={<SchedeAcquisti />}
      />
      <div className="contenitore pagina__corpo">
        {risultato.stato === 'caricamento' && <p className="attesa">Carico i fornitori…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && risultato.dati.length === 0 && (
          <div className="vuoto">
            <p>Nessun fornitore registrato.</p>
          </div>
        )}
        {risultato.stato === 'pronto' && risultato.dati.length > 0 && (
          <ul className="carte">
            {risultato.dati.map((fornitore) => (
              <li key={fornitore.id} className="carta">
                <h2 className="carta__titolo">
                  <Link to={PERCORSI_ACQUISTI.fornitore(fornitore.id)} className="carta__collegamento">
                    {fornitore.nome}
                  </Link>
                </h2>
                <p className="carta__botanica">{fornitore.origine}</p>
                <p className="carta__dati">{riassunto(fornitore)}</p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

/** GET /api/magazzino/fornitori/{id}: il riepilogo e lo storico, dalla consegna più recente. */
export function SchedaFornitore() {
  const { id = '' } = useParams();
  const { risultato, ricarica } = useCaricamento(() => magazzino.fornitore(id), id);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico il fornitore…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovato = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Fornitore · GinVault</title>
        <TestataPagina
          ritorno={{ a: PERCORSI_ACQUISTI.fornitori, testo: 'Tutti i fornitori' }}
          titolo={nonTrovato ? 'Fornitore non trovato' : 'Fornitore'}
          attacco={nonTrovato ? 'L’indirizzo non corrisponde a nessun fornitore.' : undefined}
        />
        {!nonTrovato && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const fornitore = risultato.dati;
  const { consegne, puntuali, inRitardo, ridotte } = fornitore.riepilogo;
  return (
    <section className="pagina">
      <title>{`${fornitore.nome} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PERCORSI_ACQUISTI.fornitori, testo: 'Tutti i fornitori' }}
        titolo={fornitore.nome}
        attacco={`Da ${fornitore.origine}. ${riassunto(fornitore)}`}
      />
      {consegne > 0 && (
        <Indicatori
          etichetta="Le consegne in numeri"
          voci={[
            { chiave: 'consegne', etichetta: consegne === 1 ? 'consegna' : 'consegne', valore: numero(consegne), principale: true },
            { chiave: 'puntuali', etichetta: 'puntuali', valore: numero(puntuali) },
            { chiave: 'ritardo', etichetta: 'in ritardo', valore: numero(inRitardo) },
            { chiave: 'ridotte', etichetta: ridotte === 1 ? 'ridotta' : 'ridotte', valore: numero(ridotte) },
          ]}
        />
      )}
      <div className="contenitore pagina__corpo">
        <section aria-labelledby="storico-titolo">
          <h2 id="storico-titolo">Lo storico</h2>
          {fornitore.storico.length === 0 ? (
            <p className="nota">Nessuna consegna ancora: lo storico parte dalla prima.</p>
          ) : (
            <ol className="storico">
              {fornitore.storico.map((voce) => (
                <VoceDelloStorico key={voce.ordineId} voce={voce} />
              ))}
            </ol>
          )}
        </section>
      </div>
    </section>
  );
}

function VoceDelloStorico({ voce }: { voce: VoceStorico }) {
  const giorno = partiDelGiorno(`${voce.consegnataIl}T12:00:00Z`);
  const segnata = voce.ridotta || voce.inRitardo;
  return (
    <li className={segnata ? 'storico__voce storico__voce--segnata' : 'storico__voce'}>
      <p className="storico__data">
        <span className="storico__giorno">{giorno.giorno}</span> {giorno.mese} {giorno.anno}
      </p>
      <div className="storico__testo">
        <h3 className="storico__titolo">
          <Link to={PERCORSI_ACQUISTI.ordine(voce.ordineId)}>
            {voce.ridotta ? `${numero(voce.kgArrivati)} su ${kg(voce.kgOrdinati)}` : kg(voce.kgArrivati)} di {voce.botanica}
          </Link>
        </h3>
        <p className="storico__esito">
          {voce.ridotta ? 'Ridotta' : 'Completa'},{' '}
          {voce.inRitardo ? `in ritardo di ${giorni(voce.giorniDiRitardo)}` : 'puntuale'}.
        </p>
        {voce.nota && <p className="citazione">«{voce.nota}»</p>}
      </div>
    </li>
  );
}
