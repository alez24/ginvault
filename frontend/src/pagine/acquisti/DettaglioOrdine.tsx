import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';

import { ErroreApi } from '../../api/http';
import { magazzino, NOMI_ESITI_QUALITA, type EsitoConsegna, type EsitoQualita, type Ordine } from '../../api/magazzino';
import {
  AvvisoConsegnaRidotta,
  EtichetteOrdine,
  MisuraConsegna,
  PERCORSI_ACQUISTI,
} from '../../componenti/Acquisti';
import { Avviso } from '../../componenti/Avviso';
import { AvvisoDiArrivo, Ricetta } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { giorni, ilGiorno, kg, numero, oggi, perCampo } from '../../utilita/numeri';
import {
  consegnaPrevista,
  controllaConsegna,
  NOTA_MASSIMA,
  testoConsegnaRidotta,
  type TestiConsegna,
} from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';

/** GET /api/magazzino/ordini/{id}: l'ordine, e la sua consegna (registrata qui se manca). */
export function DettaglioOrdine() {
  const { id = '' } = useParams();
  const { risultato, ricarica, imposta } = useCaricamento(() => magazzino.ordine(id), id);
  const [registrata, setRegistrata] = useState<EsitoConsegna | null>(null);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico l’ordine…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovato = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Ordine · GinVault</title>
        <TestataPagina
          ritorno={{ a: PERCORSI_ACQUISTI.ordini, testo: 'Tutti gli ordini' }}
          titolo={nonTrovato ? 'Ordine non trovato' : 'Ordine'}
          attacco={nonTrovato ? 'L’indirizzo non corrisponde a nessun ordine.' : undefined}
        />
        {!nonTrovato && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const ordine = risultato.dati;
  const consegna = ordine.consegna;
  const titolo = `${kg(ordine.kgOrdinati)} di ${ordine.botanica}`;

  return (
    <section className="pagina">
      <title>{`${titolo} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PERCORSI_ACQUISTI.ordini, testo: 'Tutti gli ordini' }}
        sopra={<EtichetteOrdine ordine={ordine} />}
        titolo={titolo}
        attacco={
          <>
            Da <Link to={PERCORSI_ACQUISTI.fornitore(ordine.fornitore.id)}>{ordine.fornitore.nome}</Link>, ordinati{' '}
            {ilGiorno(ordine.creatoIl)} e attesi {ilGiorno(ordine.consegnaAttesa)}.
          </>
        }
      />
      <Indicatori
        etichetta="L’ordine in numeri"
        voci={[
          { chiave: 'ordinati', etichetta: 'ordinati', valore: numero(ordine.kgOrdinati), unita: 'kg' },
          {
            chiave: 'arrivati',
            etichetta: consegna ? 'arrivati' : 'arrivati finora',
            valore: numero(consegna?.kgArrivati ?? 0),
            unita: 'kg',
            principale: true,
          },
          ...(consegna
            ? [{ chiave: 'ritardo', etichetta: 'di ritardo', valore: numero(consegna.giorniDiRitardo), unita: consegna.giorniDiRitardo === 1 ? 'giorno' : 'giorni' }]
            : []),
        ]}
      />

      <div className="contenitore pagina__corpo">
        <AvvisoDiArrivo />
        {registrata && <ConsegnaRegistrata esito={registrata} onChiudi={() => setRegistrata(null)} />}

        {consegna ? (
          <div className="due-colonne">
            <section className="scheda" aria-labelledby="consegna-titolo">
              <h2 id="consegna-titolo">La consegna</h2>
              <MisuraConsegna ordine={ordine} />
              {consegna.ridotta && !registrata && (
                <div className="spazio-sopra">
                  <AvvisoConsegnaRidotta consegna={consegna} kgOrdinati={ordine.kgOrdinati} />
                </div>
              )}
              {/* .ricetta azzera i margini: lo spazio lo dà il contenitore */}
              <div className="spazio-sopra">
                <Ricetta
                  voci={[
                    { chiave: 'arrivati', nome: 'Arrivati', valore: kg(consegna.kgArrivati), evidenza: true },
                    { chiave: 'mancanti', nome: 'Non arriveranno', valore: kg(consegna.kgMancanti) },
                    { chiave: 'quando', nome: 'Consegnata', valore: ilGiorno(consegna.consegnataIl) },
                    {
                      chiave: 'ritardo',
                      nome: 'Ritardo',
                      valore: consegna.inRitardo ? giorni(consegna.giorniDiRitardo) : 'in orario',
                    },
                    { chiave: 'origine', nome: 'Origine', valore: consegna.origine },
                  ]}
                />
              </div>
              {consegna.nota && <p className="citazione">«{consegna.nota}»</p>}
            </section>
            <aside className="scheda">
              <h2>Il lotto</h2>
              <p>
                La consegna è diventata un lotto del magazzino, con l’origine del fornitore e la scadenza: la produzione lo
                vede subito.
              </p>
              <Link className="pulsante pulsante--contorno" to={PERCORSI_ACQUISTI.lotto(consegna.lottoId)}>
                Vedi il lotto
              </Link>
            </aside>
          </div>
        ) : (
          <ModuloConsegna
            ordine={ordine}
            onRegistrata={(esito) => {
              imposta(esito.ordine);
              setRegistrata(esito);
            }}
            onRicarica={ricarica}
          />
        )}
      </div>
    </section>
  );
}

/** L'esito della POST: l'avviso della consegna ridotta (se lo è) e il lotto nuovo. */
function ConsegnaRegistrata({ esito, onChiudi }: { esito: EsitoConsegna; onChiudi: () => void }) {
  const consegna = esito.ordine.consegna;
  const titolo = useRef<HTMLDivElement>(null);
  useEffect(() => {
    titolo.current?.scrollIntoView({ block: 'nearest' });
  }, []);
  if (!consegna) {
    return null;
  }
  return (
    <div className="spazio-sotto" ref={titolo}>
      {consegna.ridotta ? (
        <Avviso tipo="attenzione" titolo={testoConsegnaRidotta(consegna.kgArrivati, esito.ordine.kgOrdinati)} onChiudi={onChiudi}>
          <p>
            Consegna registrata. Mancano {kg(consegna.kgMancanti)}: l’ordine è chiuso e il resto non arriverà. Il{' '}
            <Link to={PERCORSI_ACQUISTI.lotto(esito.lotto.id)}>lotto da {kg(esito.lotto.kg)}</Link> è in magazzino;
            Produzione ricalcola i kg del piano ed Edizioni la tiratura, senza che nessuno faccia niente.
          </p>
        </Avviso>
      ) : (
        <Avviso titolo="Consegna registrata" onChiudi={onChiudi}>
          <p>
            Sono arrivati tutti i {kg(consegna.kgArrivati)}. Il{' '}
            <Link to={PERCORSI_ACQUISTI.lotto(esito.lotto.id)}>lotto</Link> è in magazzino.
          </p>
        </Avviso>
      )}
    </div>
  );
}

interface PropsConsegna {
  ordine: Ordine;
  onRegistrata: (esito: EsitoConsegna) => void;
  onRicarica: () => void;
}

const ESITI: EsitoQualita[] = ['CONFORME', 'NON_CONFORME'];

/**
 * POST /ordini/{id}/consegne. Anche ridotta: una consegna per ordine, e chiude
 * l'ordine. L'anteprima dice subito se la consegna è ridotta o in ritardo.
 */
function ModuloConsegna({ ordine, onRegistrata, onRicarica }: PropsConsegna) {
  const oggiARoma = oggi();
  const [testi, setTesti] = useState<TestiConsegna>({ kg: '', consegnataIl: oggiARoma, scadenza: '', esito: '', nota: '' });
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  const { consegna, errori } = controllaConsegna(testi, ordine, oggiARoma);
  const cambia = (campo: keyof TestiConsegna) => (valore: string) => setTesti((t) => ({ ...t, [campo]: valore }));
  const sbagliato = (campo: keyof TestiConsegna) => (mostraErrori && errori[campo] ? true : undefined);
  const errorePer = (campo: keyof TestiConsegna, idCampo: string) =>
    mostraErrori && errori[campo] ? (
      <p className="campo__errore" id={`${idCampo}-errore`}>
        {errori[campo]}
      </p>
    ) : null;
  const descritto = (campo: keyof TestiConsegna, idCampo: string) =>
    mostraErrori && errori[campo] ? `${idCampo}-errore` : undefined;

  // L'anteprima appena i kg si leggono, anche se il resto del modulo non è finito
  const kgLetti = errori.kg ? undefined : Number(testi.kg.trim().replace(',', '.'));
  const prevista = kgLetti !== undefined && testi.kg.trim() !== '' ? consegnaPrevista(ordine, kgLetti, testi.consegnataIl) : undefined;

  const registra = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (!consegna) {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      onRegistrata(await magazzino.registraConsegna(ordine.id, consegna));
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
    } finally {
      setInvio(false);
    }
  };

  return (
    <form className="modulo scheda scheda--stretta" onSubmit={(evento) => void registra(evento)} noValidate>
      <h2>Registra la consegna</h2>
      <p>
        Una consegna per ordine, anche se arrivano meno kg: l’ordine si chiude e il magazzino crea il lotto. Il controllo
        qualità decide se il lotto si potrà usare.
      </p>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="kg-arrivati">
          Kg di {ordine.botanica} arrivati
        </label>
        <span className="campo__con-unita">
          <input
            id="kg-arrivati"
            className="campo__input campo__input--corto"
            value={testi.kg}
            onChange={(evento) => cambia('kg')(evento.target.value)}
            inputMode="decimal"
            placeholder={perCampo(ordine.kgOrdinati)}
            autoComplete="off"
            aria-invalid={sbagliato('kg')}
            aria-describedby={descritto('kg', 'kg-arrivati') ?? 'consegna-anteprima'}
          />
          <span className="campo__unita">kg su {numero(ordine.kgOrdinati)}</span>
        </span>
        {errorePer('kg', 'kg-arrivati')}
      </div>

      <div className="campi-in-fila">
        <div className="campo">
          <label className="campo__etichetta" htmlFor="consegnata-il">
            Arrivata il
          </label>
          <input
            id="consegnata-il"
            type="date"
            className="campo__input campo__input--data"
            value={testi.consegnataIl}
            max={oggiARoma}
            onChange={(evento) => cambia('consegnataIl')(evento.target.value)}
            aria-invalid={sbagliato('consegnataIl')}
            aria-describedby={descritto('consegnataIl', 'consegnata-il')}
          />
          {errorePer('consegnataIl', 'consegnata-il')}
        </div>
        <div className="campo">
          <label className="campo__etichetta" htmlFor="scadenza">
            Scadenza del lotto
          </label>
          <input
            id="scadenza"
            type="date"
            className="campo__input campo__input--data"
            value={testi.scadenza}
            onChange={(evento) => cambia('scadenza')(evento.target.value)}
            aria-invalid={sbagliato('scadenza')}
            aria-describedby={descritto('scadenza', 'scadenza')}
          />
          {errorePer('scadenza', 'scadenza')}
        </div>
      </div>

      <fieldset aria-describedby={descritto('esito', 'esito')}>
        <legend>Controllo qualità</legend>
        <div className="scelte">
          {ESITI.map((esito) => (
            <label key={esito} className="scelta">
              <input
                type="radio"
                name="esito"
                value={esito}
                checked={testi.esito === esito}
                onChange={() => cambia('esito')(esito)}
              />
              <span>
                <span className="scelta__nome">{NOMI_ESITI_QUALITA[esito]}</span>
                <span className="scelta__aiuto">
                  {esito === 'CONFORME'
                    ? 'Umidità, profumo e impurità a posto: il lotto si userà per produrre.'
                    : 'Il lotto resta in magazzino, tracciato, ma non si usa per produrre.'}
                </span>
              </span>
            </label>
          ))}
        </div>
        {errorePer('esito', 'esito')}
      </fieldset>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="nota">
          Nota <span className="campo__facoltativo">(facoltativa)</span>
        </label>
        <textarea
          id="nota"
          className="campo__input campo__input--testo"
          value={testi.nota}
          onChange={(evento) => cambia('nota')(evento.target.value)}
          placeholder="Per esempio: pioggia durante la fioritura"
          rows={2}
          maxLength={NOTA_MASSIMA + 50}
          aria-invalid={sbagliato('nota')}
          aria-describedby={descritto('nota', 'nota') ?? 'nota-aiuto'}
        />
        {errorePer('nota', 'nota') ?? (
          <p className="campo__aiuto" id="nota-aiuto">
            Finisce nello storico del fornitore.
          </p>
        )}
      </div>

      <div id="consegna-anteprima" aria-live="polite">
        {prevista && <AnteprimaConsegna ordine={ordine} kgArrivati={kgLetti ?? 0} prevista={prevista} nonConforme={testi.esito === 'NON_CONFORME'} />}
        {!prevista && !mostraErrori && errori.kg && testi.kg.trim() !== '' && <p className="anteprima">{errori.kg}</p>}
      </div>

      {errore && (
        <ErroreOperazione errore={errore} onRiprova={() => void registra()} onRicarica={onRicarica} onChiudi={() => setErrore(null)} />
      )}

      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={invio}>
          {invio ? 'Registro la consegna…' : 'Registra la consegna'}
        </button>
      </div>
    </form>
  );
}

function AnteprimaConsegna({
  ordine,
  kgArrivati,
  prevista,
  nonConforme,
}: {
  ordine: Ordine;
  kgArrivati: number;
  prevista: ReturnType<typeof consegnaPrevista>;
  nonConforme: boolean;
}) {
  const ritardo = prevista.inRitardo
    ? ` Arriva ${giorni(prevista.giorniDiRitardo)} dopo la data attesa: risulterà anche in ritardo.`
    : '';
  const qualita = nonConforme ? ' Non conforme: per l’edizione è come se non fosse arrivata.' : '';
  if (prevista.ridotta) {
    return (
      <Avviso tipo="attenzione" titolo={testoConsegnaRidotta(kgArrivati, ordine.kgOrdinati)}>
        <p>
          Mancano {kg(prevista.kgMancanti)}: l’ordine si chiude lo stesso e il resto non arriverà.
          {ritardo}
          {qualita}
        </p>
      </Avviso>
    );
  }
  return (
    <p className="anteprima">
      Arrivano tutti i {kg(ordine.kgOrdinati)} ordinati.
      {ritardo}
      {qualita}
    </p>
  );
}
