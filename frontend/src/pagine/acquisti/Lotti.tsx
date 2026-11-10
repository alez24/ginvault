import { Link, useParams, useSearchParams } from 'react-router';

import { magazzino, type Lotto } from '../../api/magazzino';
import { EtichetteLotto, PERCORSI_ACQUISTI, SchedeAcquisti } from '../../componenti/Acquisti';
import { Ricetta } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Rametto } from '../../componenti/illustrazioni/Illustrazioni';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { data, ilGiorno, kg, maiuscola, numero } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/** "tutti liberi", "1,2 kg riservati per la produzione". */
function riservati(lotto: Lotto): string {
  const usati = Math.round((lotto.kg - lotto.kgDisponibili) * 1000) / 1000;
  if (usati <= 0) {
    return 'tutti liberi';
  }
  return lotto.kgDisponibili <= 0 ? 'tutti riservati per la produzione' : `${kg(usati)} riservati per la produzione`;
}

/** GET /api/magazzino/lotti, dal più recente; il filtro per botanica sta nell'indirizzo (?botanica=lavanda). */
export function ElencoLotti() {
  const [parametri] = useSearchParams();
  const botanica = parametri.get('botanica') ?? undefined;
  const { risultato, ricarica } = useCaricamento(() => magazzino.lotti(), 'lotti');
  const lotti = risultato.stato === 'pronto' ? risultato.dati : [];
  const botaniche = [...new Set(lotti.map((lotto) => lotto.botanica))].sort((a, b) => a.localeCompare(b, 'it'));
  const mostrati = botanica ? lotti.filter((lotto) => lotto.botanica === botanica) : lotti;

  return (
    <section className="pagina">
      <title>Lotti · GinVault</title>
      <TestataPagina
        titolo="Lotti"
        attacco="Ogni consegna diventa un lotto, con l’origine del fornitore, la scadenza e il controllo qualità. La produzione usa solo i lotti conformi dell’origine che l’edizione chiede."
        piede={
          <>
            <SchedeAcquisti />
            {botaniche.length > 1 && (
              <nav className="filtri filtri--secondari" aria-label="Filtra per botanica">
                <Link to={PERCORSI_ACQUISTI.lotti} className="filtri__voce" aria-current={botanica ? undefined : 'page'}>
                  Tutte le botaniche
                </Link>
                {botaniche.map((nome) => (
                  <Link
                    key={nome}
                    to={`?botanica=${encodeURIComponent(nome)}`}
                    className="filtri__voce"
                    aria-current={nome === botanica ? 'page' : undefined}
                  >
                    {maiuscola(nome)}
                  </Link>
                ))}
              </nav>
            )}
          </>
        }
      />

      <div className="contenitore pagina__corpo">
        {risultato.stato === 'caricamento' && <p className="attesa">Carico i lotti…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && mostrati.length === 0 && (
          <div className="vuoto">
            <p>{botanica ? `Nessun lotto di ${botanica}.` : 'Nessun lotto in magazzino: nascono dalle consegne.'}</p>
          </div>
        )}
        {mostrati.length > 0 && (
          <ul className="carte">
            {mostrati.map((lotto) => (
              <li key={lotto.id} className={lotto.utilizzabile ? 'carta' : 'carta carta--spenta'}>
                <Rametto botanica={lotto.botanica} className="carta__rametto" />
                <EtichetteLotto lotto={lotto} />
                <h2 className="carta__titolo">
                  <Link to={PERCORSI_ACQUISTI.lotto(lotto.id)} className="carta__collegamento">
                    {kg(lotto.kg)} di {lotto.botanica}
                  </Link>
                </h2>
                <p className="carta__botanica">{lotto.origine}</p>
                <p className="carta__dati">
                  {lotto.utilizzabile ? maiuscola(riservati(lotto)) : 'Non si usa per produrre'}. Da {lotto.fornitore.nome}, arrivato {ilGiorno(lotto.ricevutoIl)}, scade{' '}
                  {ilGiorno(lotto.scadenza)}.
                </p>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

/** GET /api/magazzino/lotti/{id}: la tracciabilità del lotto. */
export function DettaglioLotto() {
  const { id = '' } = useParams();
  const { risultato, ricarica } = useCaricamento(() => magazzino.lotto(id), id);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico il lotto…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovato = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Lotto · GinVault</title>
        <TestataPagina
          ritorno={{ a: PERCORSI_ACQUISTI.lotti, testo: 'Tutti i lotti' }}
          titolo={nonTrovato ? 'Lotto non trovato' : 'Lotto'}
          attacco={nonTrovato ? 'L’indirizzo non corrisponde a nessun lotto.' : undefined}
        />
        {!nonTrovato && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const lotto = risultato.dati;
  const titolo = `${kg(lotto.kg)} di ${lotto.botanica}`;
  return (
    <section className="pagina">
      <title>{`Lotto, ${titolo} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PERCORSI_ACQUISTI.lotti, testo: 'Tutti i lotti' }}
        sopra={<EtichetteLotto lotto={lotto} />}
        titolo={titolo}
        attacco={`Da ${lotto.origine}.`}
      />
      <Indicatori
        etichetta="Il lotto in numeri"
        voci={[
          { chiave: 'kg', etichetta: 'nel lotto', valore: numero(lotto.kg), unita: 'kg' },
          { chiave: 'liberi', etichetta: 'liberi per la produzione', valore: numero(lotto.kgDisponibili), unita: 'kg', principale: true },
        ]}
      />
      <div className="contenitore pagina__corpo">
        <div className="scheda scheda--stretta">
          <Ricetta
            voci={[
              { chiave: 'origine', nome: 'Origine', valore: lotto.origine, evidenza: true },
              {
                chiave: 'fornitore',
                nome: 'Fornitore',
                valore: <Link to={PERCORSI_ACQUISTI.fornitore(lotto.fornitore.id)}>{lotto.fornitore.nome}</Link>,
              },
              { chiave: 'arrivo', nome: 'Arrivato', valore: data(lotto.ricevutoIl) },
              { chiave: 'scadenza', nome: 'Scade', valore: data(lotto.scadenza) },
              { chiave: 'riservati', nome: 'Riserve', valore: riservati(lotto) },
              {
                chiave: 'ordine',
                nome: 'Ordine',
                valore: lotto.ordineId ? <Link to={PERCORSI_ACQUISTI.ordine(lotto.ordineId)}>vedi l’ordine</Link> : 'nessuno',
              },
            ]}
          />
          <p className="nota spazio-sopra">
            {lotto.utilizzabile
              ? 'Si può usare per produrre, ma solo per le edizioni che chiedono questa origine.'
              : lotto.esitoQualita === 'NON_CONFORME'
                ? 'Non conforme: resta in magazzino, tracciato, ma non si usa per produrre.'
                : 'Scaduto: non si usa più per produrre.'}
            {!lotto.ordineId && ' Non viene da un ordine: è entrato in magazzino direttamente, come il raccolto dei campi.'}
          </p>
        </div>
      </div>
    </section>
  );
}
