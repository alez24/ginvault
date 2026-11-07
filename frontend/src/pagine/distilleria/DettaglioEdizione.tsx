import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';

import { CANALI, edizioni, NOMI_CANALI, type Edizione } from '../../api/edizioni';
import { ErroreApi } from '../../api/http';
import { ricette } from '../../api/ricette';
import { Bottiglia, Cartellino } from '../../componenti/Bottiglia';
import { BOTTIGLIE_PER_DISEGNO, QuoteInBottiglie } from '../../componenti/Bottiglie';
import { AvvisoDiArrivo, Ricetta } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import {
  bottiglie,
  differenza,
  edizioneDellaStagione,
  ilGiorno,
  maiuscola,
  numero,
  perCampo,
} from '../../utilita/numeri';
import { controllaKg, ricalcoloPrevisto, TIRATURA_MASSIMA } from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';
import { nomeEdizione } from './ElencoEdizioni';

interface Confronto {
  prima: Edizione;
  dopo: Edizione;
  /** Cambia a ogni ricalcolo: le bottiglie ripartono con l'animazione. */
  giro: number;
}

/** GET /api/edizioni/edizioni/{id}: tiratura, quote e ricalcolo con nuova disponibilità. */
export function DettaglioEdizione() {
  const { id = '' } = useParams();
  const { risultato, ricarica, imposta } = useCaricamento(() => edizioni.trova(id), id);
  const [confronto, setConfronto] = useState<Confronto | null>(null);
  const formulazioneId = risultato.stato === 'pronto' ? risultato.dati.formulazione.id : undefined;
  const nome = useNomeFormulazione(formulazioneId);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico l’edizione…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovata = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Edizione · GinVault</title>
        <TestataPagina
          ritorno={{ a: PAGINE.edizioni.percorso, testo: 'Tutte le edizioni' }}
          titolo={nonTrovata ? 'Edizione non trovata' : 'Edizione'}
          attacco={nonTrovata ? 'L’indirizzo non corrisponde a nessuna edizione.' : undefined}
        />
        {!nonTrovata && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const edizione = risultato.dati;
  const titolo = nomeEdizione(nome, edizione.botanicaDiStagione);
  const ricalcolata = (prima: Edizione, dopo: Edizione) => {
    imposta(dopo);
    setConfronto((precedente) => ({ prima, dopo, giro: (precedente?.giro ?? 0) + 1 }));
  };

  const prenotate = CANALI.reduce((somma, canale) => somma + edizione.prenotate[canale], 0);
  // La lavanda è il colore della sola edizione d'estate
  const stagione = edizioneDellaStagione(edizione.creataIl);
  const estiva = stagione.includes('estate');

  return (
    <section className={estiva ? 'pagina pagina--edizione pagina--estate' : 'pagina pagina--edizione'}>
      <title>{`${titolo} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PAGINE.edizioni.percorso, testo: 'Tutte le edizioni' }}
        titolo={titolo}
        attacco={
          <>
            Dalla{' '}
            <Link to={`${PAGINE.formulazioni.percorso}/${edizione.formulazione.id}`}>
              versione {edizione.formulazione.versione}
            </Link>{' '}
            della formulazione, creata {ilGiorno(edizione.creataIl)}.
          </>
        }
        figura={
          <figure className="etichetta-edizione">
            <span className="etichetta-edizione__alone" aria-hidden="true" />
            <Bottiglia sizes="(min-width: 48rem) 7rem, 5.5rem" />
            <Cartellino edizione={stagione} sotto={`${numero(edizione.tiratura)} bottiglie numerate`} />
          </figure>
        }
      />
      <Indicatori
        etichetta="L’edizione in numeri"
        voci={[
          { chiave: 'tiratura', etichetta: 'bottiglie in tiratura', valore: numero(edizione.tiratura), principale: true },
          {
            chiave: 'kg',
            etichetta: `di ${edizione.botanicaDiStagione} disponibili`,
            valore: numero(edizione.botanicaDisponibileKg),
            unita: 'kg',
          },
          { chiave: 'grammi', etichetta: 'per bottiglia', valore: numero(edizione.grammiPerBottiglia), unita: 'g' },
          { chiave: 'prenotate', etichetta: 'già prenotate', valore: numero(prenotate) },
        ]}
      />

      <div className="contenitore pagina__corpo">
      <AvvisoDiArrivo />
      <p className="nota tiratura__regola">
        {numero(edizione.botanicaDisponibileKg)} kg di {edizione.botanicaDiStagione} a {numero(edizione.grammiPerBottiglia)} g
        per bottiglia fanno {bottiglie(edizione.tiratura)}; il massimo è {TIRATURA_MASSIMA}. Il numero sul
        cartellino segue la tiratura.
      </p>

      <div className="due-colonne">
        <section className="scheda sezione-quote" id="quote" aria-labelledby="quote-titolo">
          <h2 id="quote-titolo">Le quote</h2>
          <p className="nota">Ogni bottiglia disegnata ne vale {BOTTIGLIE_PER_DISEGNO}.</p>
          <QuoteInBottiglie
            key={`quote-${confronto?.giro ?? 0}`}
            quote={edizione.quote}
            prenotate={edizione.prenotate}
            prima={confronto?.prima.quote}
          />
          {confronto && <PrimaEDopo key={`confronto-${confronto.giro}`} {...confronto} onChiudi={() => setConfronto(null)} />}
        </section>
        <aside>
          <ModuloRicalcolo edizione={edizione} onRicalcolata={ricalcolata} onRicarica={ricarica} />
        </aside>
      </div>
      </div>
    </section>
  );
}

/** Il nome della formulazione da Ricette; undefined finché non arriva o se Ricette non risponde. */
function useNomeFormulazione(formulazioneId: string | undefined): string | undefined {
  const [nome, setNome] = useState<{ id: string; nome: string } | undefined>(undefined);
  useEffect(() => {
    if (!formulazioneId) {
      return;
    }
    let attivo = true;
    ricette.trova(formulazioneId).then(
      (formulazione) => {
        if (attivo) setNome({ id: formulazioneId, nome: formulazione.nome });
      },
      () => undefined,
    );
    return () => {
      attivo = false;
    };
  }, [formulazioneId]);
  return nome?.id === formulazioneId ? nome?.nome : undefined;
}

interface PropsRicalcolo {
  edizione: Edizione;
  onRicalcolata: (prima: Edizione, dopo: Edizione) => void;
  onRicarica: () => void;
}

/**
 * POST /{id}/ricalcolo: la nuova disponibilità di botanica. L'anteprima usa le
 * regole di Edizioni (ONLINE cala per primo, poi BAR, SOCI mai); se il
 * ricalcolo non è possibile lo si può inviare lo stesso e Edizioni spiega perché (409).
 */
function ModuloRicalcolo({ edizione, onRicalcolata, onRicarica }: PropsRicalcolo) {
  const [kg, setKg] = useState('');
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);
  const stagione = edizione.botanicaDiStagione;

  const kgLetti = controllaKg(kg);
  const anteprima = typeof kgLetti === 'number' ? ricalcoloPrevisto(edizione, kgLetti) : undefined;

  const ricalcola = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (typeof kgLetti !== 'number') {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      const dopo = await edizioni.ricalcola(edizione.id, kgLetti);
      onRicalcolata(edizione, dopo);
      setKg('');
      setMostraErrori(false);
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
    } finally {
      setInvio(false);
    }
  };

  return (
    <form className="modulo scheda" onSubmit={(evento) => void ricalcola(evento)} noValidate>
      <h2>Ricalcola con nuova disponibilità</h2>
      <p>
        Se il raccolto di {stagione} cambia, la tiratura si ricalcola. Le bottiglie in meno si tolgono prima
        dall’online, poi dai bar; la quota dei soci non si tocca e nessuna quota scende sotto le prenotate.
      </p>

      <div className="campo">
        <label className="campo__etichetta" htmlFor="nuovi-kg">
          Kg di {stagione} disponibili ora
        </label>
        <span className="campo__con-unita">
          <input
            id="nuovi-kg"
            className="campo__input campo__input--corto"
            value={kg}
            onChange={(evento) => setKg(evento.target.value)}
            inputMode="decimal"
            placeholder={perCampo(edizione.botanicaDisponibileKg)}
            autoComplete="off"
            aria-invalid={mostraErrori && typeof kgLetti === 'string' ? true : undefined}
            aria-describedby="ricalcolo-anteprima"
          />
          <span className="campo__unita">kg</span>
        </span>
      </div>

      <div className="anteprima" id="ricalcolo-anteprima" aria-live="polite">
        {typeof kgLetti === 'string' ? (
          mostraErrori || kg.trim() ? (
            <p className={mostraErrori ? 'campo__errore' : undefined}>{kgLetti}</p>
          ) : (
            <p>Ora ce ne sono {numero(edizione.botanicaDisponibileKg)} kg. Scrivi la nuova quantità per l’anteprima.</p>
          )
        ) : (
          anteprima && <Anteprima edizione={edizione} kg={kgLetti} anteprima={anteprima} />
        )}
      </div>

      {errore && (
        <ErroreOperazione
          errore={errore}
          onRiprova={() => void ricalcola()}
          onRicarica={onRicarica}
          onChiudi={() => setErrore(null)}
        />
      )}

      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={invio}>
          {invio ? 'Ricalcolo…' : 'Ricalcola la tiratura'}
        </button>
      </div>
    </form>
  );
}

function Anteprima({
  edizione,
  kg,
  anteprima,
}: {
  edizione: Edizione;
  kg: number;
  anteprima: ReturnType<typeof ricalcoloPrevisto>;
}) {
  switch (anteprima.esito) {
    case 'nessuna-bottiglia':
      return (
        <p>
          Con {numero(kg)} kg non si riempie nemmeno una bottiglia ({numero(edizione.grammiPerBottiglia)} g ciascuna):
          Edizioni rifiuterà il ricalcolo.
        </p>
      );
    case 'non-riducibile':
      return (
        <p>
          La tiratura scenderebbe da {numero(edizione.tiratura)} a {bottiglie(anteprima.tiratura)}: ne vanno tolte{' '}
          {numero(anteprima.daTogliere)}, ma online e bar ne hanno solo {numero(anteprima.libere)} non prenotate e i
          soci non si toccano. Edizioni rifiuterà il ricalcolo.
        </p>
      );
    case 'possibile': {
      const scarto = anteprima.tiratura - edizione.tiratura;
      if (scarto === 0) {
        return <p>La tiratura resta di {bottiglie(edizione.tiratura)}: cambiano solo i kg disponibili.</p>;
      }
      const cambiati = CANALI.filter((canale) => anteprima.quote[canale] !== edizione.quote[canale]);
      return (
        <p>
          Tiratura da {numero(edizione.tiratura)} a <strong>{bottiglie(anteprima.tiratura)}</strong> ({differenza(scarto)}):{' '}
          {cambiati
            .map(
              (canale) =>
                `${NOMI_CANALI[canale].toLowerCase()} ${numero(edizione.quote[canale])} → ${numero(anteprima.quote[canale])}`,
            )
            .join(', ')}
          .
        </p>
      );
    }
  }
}

/** Il risultato del ricalcolo: la risposta di Edizioni confrontata con l'edizione di prima. */
function PrimaEDopo({ prima, dopo, onChiudi }: Confronto & { onChiudi: () => void }) {
  const scesa = dopo.tiratura < prima.tiratura;
  const valore = (da: number, a: number, unita = '') => (
    <>
      {numero(da)} → {numero(a)}
      {unita}
      {da !== a && <span className="ricetta__scarto">{differenza(Math.round((a - da) * 1000) / 1000)}</span>}
    </>
  );

  // Su telefono il modulo è sotto: dopo ogni ricalcolo si torna alle quote per vedere
  // le bottiglie che si svuotano; il focus sul titolo fa leggere il risultato agli screen reader
  const titolo = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const fermo = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    titolo.current?.focus({ preventScroll: true });
    document.getElementById('quote')?.scrollIntoView({ behavior: fermo ? 'auto' : 'smooth', block: 'start' });
  }, []);

  return (
    <div className="confronto">
      <div className="confronto__testa">
        <h3 ref={titolo} tabIndex={-1}>
          {dopo.tiratura === prima.tiratura
            ? `La tiratura resta di ${bottiglie(dopo.tiratura)}`
            : `La tiratura è ${scesa ? 'scesa' : 'salita'} da ${numero(prima.tiratura)} a ${numero(dopo.tiratura)} bottiglie`}
        </h3>
        <button type="button" className="avviso__chiudi" onClick={onChiudi} aria-label="Chiudi il confronto">
          ×
        </button>
      </div>
      <Ricetta
        voci={[
          {
            chiave: 'kg',
            nome: `${maiuscola(prima.botanicaDiStagione)} disponibile`,
            valore: valore(prima.botanicaDisponibileKg, dopo.botanicaDisponibileKg, ' kg'),
          },
          { chiave: 'tiratura', nome: 'Tiratura', valore: valore(prima.tiratura, dopo.tiratura), evidenza: true },
          ...CANALI.map((canale) => ({
            chiave: canale,
            nome: NOMI_CANALI[canale],
            valore: valore(prima.quote[canale], dopo.quote[canale]),
          })),
        ]}
      />
      {scesa && (
        <p className="nota">Edizioni ha registrato l’evento TiraturaRidotta: servirà ad avvisare chi ha prenotato.</p>
      )}
    </div>
  );
}
