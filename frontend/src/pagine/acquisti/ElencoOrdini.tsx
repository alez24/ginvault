import { Link } from 'react-router';

import { magazzino, type Ordine } from '../../api/magazzino';
import { EtichetteOrdine, MisuraConsegna, PERCORSI_ACQUISTI, SchedeAcquisti } from '../../componenti/Acquisti';
import { AvvisoDiArrivo } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { ilGiorno, kg, numero, oggi } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/** GET /api/magazzino/ordini: prima quelli da ricevere, poi quelli consegnati. */
export function ElencoOrdini() {
  const { risultato, ricarica } = useCaricamento(() => magazzino.ordini(), 'ordini');
  const oggiARoma = oggi();

  return (
    <section className="pagina">
      <title>Ordini · GinVault</title>
      <TestataPagina
        titolo="Acquisti"
        attacco="Le botaniche si ordinano per un’edizione. Quando arrivano si registra la consegna, anche se è ridotta: il magazzino crea il lotto e la produzione ricalcola da sola."
        azioni={
          <Link to={PERCORSI_ACQUISTI.nuovoOrdine} className="pulsante">
            Nuovo ordine
          </Link>
        }
        piede={<SchedeAcquisti />}
      />
      {risultato.stato === 'pronto' && risultato.dati.length > 0 && <NumeriDegliOrdini ordini={risultato.dati} />}

      <div className="contenitore pagina__corpo">
        <AvvisoDiArrivo />
        {risultato.stato === 'caricamento' && <p className="attesa">Carico gli ordini…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && risultato.dati.length === 0 && (
          <div className="vuoto">
            <p>Nessun ordine ancora. Il primo si fa per l’edizione che ha bisogno della botanica.</p>
            <Link to={PERCORSI_ACQUISTI.nuovoOrdine}>Fai un ordine</Link>
          </div>
        )}
        {risultato.stato === 'pronto' && risultato.dati.length > 0 && (
          <>
            <Gruppo
              titolo="Da ricevere"
              vuoto="Nessun ordine in attesa: tutto quello che era stato ordinato è arrivato."
              ordini={risultato.dati.filter((ordine) => ordine.stato === 'APERTO')}
              oggiARoma={oggiARoma}
            />
            <Gruppo
              titolo="Consegnati"
              vuoto="Nessuna consegna registrata ancora."
              ordini={risultato.dati.filter((ordine) => ordine.stato === 'CONSEGNATO')}
              oggiARoma={oggiARoma}
            />
          </>
        )}
      </div>
    </section>
  );
}

function Gruppo({ titolo, vuoto, ordini, oggiARoma }: { titolo: string; vuoto: string; ordini: Ordine[]; oggiARoma: string }) {
  const id = `gruppo-${titolo.toLowerCase().replace(/\s+/g, '-')}`;
  return (
    <section className="gruppo" aria-labelledby={id}>
      <h2 id={id} className="gruppo__titolo">
        {titolo} <span className="gruppo__quanti">{numero(ordini.length)}</span>
      </h2>
      {ordini.length === 0 ? (
        <p className="nota">{vuoto}</p>
      ) : (
        <ul className="carte">
          {ordini.map((ordine) => (
            <li key={ordine.id} className="carta carta--ordine">
              <EtichetteOrdine ordine={ordine} oggiARoma={oggiARoma} />
              <h3 className="carta__titolo">
                <Link to={PERCORSI_ACQUISTI.ordine(ordine.id)} className="carta__collegamento">
                  {kg(ordine.kgOrdinati)} di {ordine.botanica}
                </Link>
              </h3>
              <p className="carta__botanica">{ordine.fornitore.nome}</p>
              <MisuraConsegna ordine={ordine} />
              <p className="carta__dati">
                {ordine.consegna
                  ? `Arrivata ${ilGiorno(ordine.consegna.consegnataIl)}, attesa ${ilGiorno(ordine.consegnaAttesa)}.`
                  : `Attesa ${ilGiorno(ordine.consegnaAttesa)}.`}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Quanti ordini aspettano, quanti kg devono ancora arrivare, quante consegne sono state ridotte. */
function NumeriDegliOrdini({ ordini }: { ordini: Ordine[] }) {
  const aperti = ordini.filter((ordine) => ordine.stato === 'APERTO');
  const inArrivo = aperti.reduce((somma, ordine) => somma + Math.round(ordine.kgOrdinati * 1000), 0) / 1000;
  const ridotte = ordini.filter((ordine) => ordine.consegna?.ridotta).length;
  return (
    <Indicatori
      etichetta="Gli ordini in numeri"
      voci={[
        { chiave: 'aperti', etichetta: aperti.length === 1 ? 'ordine da ricevere' : 'ordini da ricevere', valore: numero(aperti.length), principale: true },
        { chiave: 'kg', etichetta: 'in arrivo', valore: numero(inArrivo), unita: 'kg' },
        { chiave: 'consegnati', etichetta: 'consegnati', valore: numero(ordini.length - aperti.length) },
        { chiave: 'ridotte', etichetta: ridotte === 1 ? 'consegna ridotta' : 'consegne ridotte', valore: numero(ridotte) },
      ]}
    />
  );
}
