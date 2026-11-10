import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';

import { rifiutoIntegrazione, type RifiutoIntegrazione } from '../../api/errori';
import { ErroreApi } from '../../api/http';
import {
  edizioneDelPiano,
  produzione,
  type LottoDelPiano,
  type MotivoRifiuto,
  type Piano,
  type RisultatoIntegrazione,
} from '../../api/produzione';
import { MisuraKg } from '../../componenti/Acquisti';
import { Avviso } from '../../componenti/Avviso';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Indicatori, TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { bottiglie, ilGiorno, kg, maiuscola, numero, perCampo } from '../../utilita/numeri';
import { controllaKg, integrazionePrevista, kgDaProporre } from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';
import { partiDelPiano } from './ElencoPiani';

/** GET /api/produzione/piani/{id}: i kg del piano, da dove vengono, e l'integrazione di un lotto. */
export function DettaglioPiano() {
  const { id = '' } = useParams();
  const { risultato, ricarica, imposta } = useCaricamento(() => produzione.piano(id), id);
  const [esito, setEsito] = useState<{ risultato: RisultatoIntegrazione; prima: Piano } | null>(null);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico il piano…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovato = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Piano di produzione · GinVault</title>
        <TestataPagina
          ritorno={{ a: PAGINE.piani.percorso, testo: 'Tutti i piani' }}
          titolo={nonTrovato ? 'Piano non trovato' : 'Piano di produzione'}
          attacco={nonTrovato ? 'L’indirizzo non corrisponde a nessun piano.' : undefined}
        />
        {!nonTrovato && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const piano = risultato.dati;
  const titolo = edizioneDelPiano(piano);

  return (
    <section className="pagina">
      <title>{`Piano, ${titolo} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PAGINE.piani.percorso, testo: 'Tutti i piani' }}
        titolo={titolo}
        attacco={
          <>
            Il piano di produzione: {kg(piano.kgPrevisti)} di {piano.botanica} da «{piano.origineRichiesta}»,{' '}
            {numero(piano.grammiPerBottiglia)} g per bottiglia.{' '}
            <Link to={`${PAGINE.edizioni.percorso}/${piano.edizioneId}`}>Vai all’edizione</Link>
          </>
        }
      />
      <Indicatori
        etichetta="Il piano in numeri"
        voci={[
          { chiave: 'disponibili', etichetta: 'disponibili', valore: numero(piano.kgDisponibili), unita: 'kg', principale: true },
          { chiave: 'previsti', etichetta: 'previsti', valore: numero(piano.kgPrevisti), unita: 'kg' },
          { chiave: 'mancanti', etichetta: 'mancanti', valore: numero(piano.kgMancanti), unita: 'kg' },
          { chiave: 'bottiglie', etichetta: `bottiglie producibili su ${piano.tiraturaPrevista}`, valore: numero(piano.bottiglieProducibili) },
        ]}
      />

      <div className="contenitore pagina__corpo">
        {esito && <EsitoIntegrazione {...esito} onChiudi={() => setEsito(null)} />}
        <div className="due-colonne">
          <div>
            <section className="scheda" aria-labelledby="kg-titolo">
              <h2 id="kg-titolo">Da dove vengono i kg</h2>
              <MisuraKg totale={piano.kgPrevisti} parti={partiDelPiano(piano)} mancanti="mancanti" />
              <p className="nota spazio-sopra">
                Contano solo i lotti conformi che vengono da «{piano.origineRichiesta}». Gli ordini non ancora consegnati
                restano tra gli attesi: un ordine arrivato ridotto toglie i kg che non arriveranno.
              </p>
            </section>
            <Consegne piano={piano} />
            {piano.integrazioni.length > 0 && <Integrazioni piano={piano} />}
          </div>
          <aside>
            <ModuloIntegrazione
              key={piano.aggiornatoIl}
              piano={piano}
              onIntegrato={(nuovo) => {
                setEsito({ risultato: nuovo, prima: piano });
                imposta(nuovo.piano);
              }}
              onRicarica={ricarica}
            />
          </aside>
        </div>
      </div>
    </section>
  );
}

function Consegne({ piano }: { piano: Piano }) {
  return (
    <section className="spazio-sopra" aria-labelledby="consegne-titolo">
      <h2 id="consegne-titolo">Le consegne</h2>
      {piano.consegne.length === 0 ? (
        <p className="nota">Nessuna consegna ancora: i {kg(piano.kgPrevisti)} sono tutti attesi.</p>
      ) : (
        <ul className="righe">
          {piano.consegne.map((consegna) => (
            <li key={consegna.ordineId} className="riga">
              <p className="riga__titolo">
                {kg(consegna.kgArrivati)} su {numero(consegna.kgOrdinati)} arrivati {ilGiorno(consegna.consegnataIl)}
              </p>
              <p className="riga__dati">
                Da «{consegna.origine}»: {kg(consegna.kgUtilizzabili)} utilizzabili.
                {Math.round(consegna.kgUtilizzabili * 1000) < Math.round(consegna.kgArrivati * 1000) &&
                  ` ${maiuscola(consegna.motivo)}.`}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Integrazioni({ piano }: { piano: Piano }) {
  return (
    <section className="spazio-sopra" aria-labelledby="integrazioni-titolo">
      <h2 id="integrazioni-titolo">I lotti integrati</h2>
      <ul className="righe">
        {piano.integrazioni.map((integrazione) => (
          <li key={integrazione.lottoId} className="riga">
            <p className="riga__titolo">
              {kg(integrazione.kg)} da «{integrazione.origine}»
            </p>
            <p className="riga__dati">Integrati {ilGiorno(integrazione.integrataIl)}, riservati in magazzino.</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Cosa è successo dopo la POST: la resa ricalcolata (Edizioni farà il resto), invariata o già integrata. */
function EsitoIntegrazione({
  risultato,
  prima,
  onChiudi,
}: {
  risultato: RisultatoIntegrazione;
  prima: Piano;
  onChiudi: () => void;
}) {
  const riquadro = useRef<HTMLDivElement>(null);
  useEffect(() => {
    riquadro.current?.scrollIntoView({ block: 'nearest' });
  }, []);
  const dopo = risultato.piano;
  return (
    <div className="spazio-sotto" ref={riquadro}>
      {risultato.esito === 'RESA_RICALCOLATA' && (
        <Avviso titolo={`Lotto integrato: da ${kg(prima.kgDisponibili)} a ${kg(dopo.kgDisponibili)}`} onChiudi={onChiudi}>
          <p>
            I kg sono riservati in magazzino e il piano ne ha {kg(dopo.kgDisponibili)}: {bottiglie(dopo.bottiglieProducibili)}.
            Produzione ha mandato la nuova resa a Edizioni, che ricalcola la tiratura da sola.
          </p>
        </Avviso>
      )}
      {risultato.esito === 'RESA_INVARIATA' && (
        <Avviso titolo="Lotto integrato" onChiudi={onChiudi}>
          <p>I kg del piano non cambiano: erano già al massimo.</p>
        </Avviso>
      )}
      {risultato.esito === 'GIA_INTEGRATO' && (
        <Avviso titolo="Lotto già integrato" onChiudi={onChiudi}>
          <p>Questo lotto era già nel piano con gli stessi kg: non è cambiato niente.</p>
        </Avviso>
      )}
    </div>
  );
}

interface PropsIntegrazione {
  piano: Piano;
  onIntegrato: (risultato: RisultatoIntegrazione) => void;
  onRicarica: () => void;
}

/**
 * POST /piani/{id}/integrazioni con i lotti di GET /piani/{id}/lotti (gRPC verso
 * il magazzino). Un lotto di un'altra origine si può provare lo stesso:
 * l'anteprima avvisa, e Produzione spiega il rifiuto (409 ORIGINE_DIVERSA).
 */
function ModuloIntegrazione({ piano, onIntegrato, onRicarica }: PropsIntegrazione) {
  const lotti = useCaricamento(() => produzione.lotti(piano.id), `${piano.id}@${piano.aggiornatoIl}`);
  const [lottoId, setLottoId] = useState('');
  const [kgTesto, setKgTesto] = useState('');
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  if (piano.kgMancanti <= 0) {
    return (
      <div className="scheda">
        <h2>Integra un lotto</h2>
        <p>Il piano ha tutti i {kg(piano.kgPrevisti)} previsti: non serve integrare.</p>
      </div>
    );
  }

  // Produzione non propone i lotti arrivati con le consegne del piano: sono già contati
  const elenco = lotti.risultato.stato === 'pronto' ? lotti.risultato.dati.lotti : [];
  const lotto = elenco.find((l) => l.lottoId === lottoId);
  const kgLetti = controllaKg(kgTesto, 'Scrivi quanti kg integrare.');
  const prevista = lotto && typeof kgLetti === 'number' ? integrazionePrevista(piano, lotto, kgLetti) : undefined;

  const scegli = (scelto: LottoDelPiano) => {
    setLottoId(scelto.lottoId);
    setKgTesto(perCampo(kgDaProporre(scelto, piano.kgMancanti)));
    setErrore(null);
  };

  const integra = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (!lotto || typeof kgLetti !== 'number') {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      onIntegrato(await produzione.integra(piano.id, lotto.lottoId, kgLetti));
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
      setInvio(false);
    }
  };

  const rifiuto = errore ? rifiutoIntegrazione(errore) : undefined;

  return (
    <form className="modulo scheda" onSubmit={(evento) => void integra(evento)} noValidate>
      <h2>Integra un lotto</h2>
      <p>
        Mancano {kg(piano.kgMancanti)} di {piano.botanica}. Un lotto del magazzino li può coprire, se viene da «
        {piano.origineRichiesta}»: l’edizione resta fedele alla sua origine.
      </p>

      {lotti.risultato.stato === 'caricamento' && <p className="attesa attesa--breve">Chiedo i lotti al magazzino…</p>}
      {lotti.risultato.stato === 'errore' && <ErroreOperazione errore={lotti.risultato.errore} onRiprova={lotti.ricarica} />}
      {lotti.risultato.stato === 'pronto' && elenco.length === 0 && (
        <p className="nota">In magazzino non c’è nessun altro lotto utilizzabile di {piano.botanica}.</p>
      )}
      {piano.consegne.length > 0 && elenco.length > 0 && (
        <p className="nota">
          {piano.consegne.length === 1
            ? 'Il lotto della consegna è già contato nel piano: non compare qui.'
            : 'I lotti delle consegne sono già contati nel piano: non compaiono qui.'}
        </p>
      )}
      {elenco.length > 0 && (
        <fieldset>
          <legend>Il lotto</legend>
          <div className="scelte">
            {elenco.map((l) => (
              <label key={l.lottoId} className={l.stessaOrigine ? 'scelta' : 'scelta scelta--diversa'}>
                <input
                  type="radio"
                  name="lotto"
                  value={l.lottoId}
                  checked={l.lottoId === lottoId}
                  onChange={() => scegli(l)}
                />
                <span>
                  <span className="scelta__nome">{l.origine}</span>
                  <span className="scelta__aiuto">
                    {kg(l.kgDisponibili)} liberi su {numero(l.kg)}, da {l.fornitore}, scade {ilGiorno(l.scadenza)}.
                  </span>
                  <span className={l.stessaOrigine ? 'stato stato--scelta' : 'stato stato--diversa'}>
                    {l.stessaOrigine ? 'Stessa origine dell’edizione' : 'Origine diversa'}
                  </span>
                </span>
              </label>
            ))}
          </div>
          {mostraErrori && !lotto && <p className="campo__errore">Scegli il lotto da integrare.</p>}
        </fieldset>
      )}

      {lotto && (
        <div className="campo">
          <label className="campo__etichetta" htmlFor="kg-integrati">
            Kg da integrare
          </label>
          <span className="campo__con-unita">
            <input
              id="kg-integrati"
              className="campo__input campo__input--corto"
              value={kgTesto}
              onChange={(evento) => setKgTesto(evento.target.value)}
              inputMode="decimal"
              autoComplete="off"
              aria-invalid={mostraErrori && typeof kgLetti === 'string' ? true : undefined}
              aria-describedby="integrazione-anteprima"
            />
            <span className="campo__unita">kg</span>
          </span>
          <div className="anteprima" id="integrazione-anteprima" aria-live="polite">
            {typeof kgLetti === 'string' ? (
              <p className={mostraErrori ? 'campo__errore' : undefined}>{kgLetti}</p>
            ) : (
              prevista && <AnteprimaIntegrazione piano={piano} lotto={lotto} prevista={prevista} />
            )}
          </div>
        </div>
      )}

      {errore && rifiuto && <RifiutoSpiegato rifiuto={rifiuto} errore={errore} piano={piano} onChiudi={() => setErrore(null)} />}
      {errore && !rifiuto && (
        <ErroreOperazione errore={errore} onRiprova={() => void integra()} onRicarica={onRicarica} onChiudi={() => setErrore(null)} />
      )}

      {elenco.length > 0 && (
        <div className="modulo__azioni">
          <button type="submit" className="pulsante" disabled={invio}>
            {invio ? 'Integro il lotto…' : 'Integra il lotto'}
          </button>
        </div>
      )}
    </form>
  );
}

function AnteprimaIntegrazione({
  piano,
  lotto,
  prevista,
}: {
  piano: Piano;
  lotto: LottoDelPiano;
  prevista: ReturnType<typeof integrazionePrevista>;
}) {
  switch (prevista.esito) {
    case 'possibile':
      return (
        <p>
          Il piano passa da {numero(piano.kgDisponibili)} a <strong>{kg(prevista.kgDopo)}</strong>:{' '}
          {bottiglie(prevista.bottiglie)}. Edizioni ricalcolerà la tiratura da sola.
        </p>
      );
    case 'origine-diversa':
      return (
        <p>
          Questo lotto viene da «{lotto.origine}», l’edizione chiede {piano.botanica} da «{piano.origineRichiesta}»:
          Produzione rifiuterà l’integrazione.
        </p>
      );
    case 'troppi':
      return <p>Al piano mancano solo {kg(prevista.kgMancanti)}: Produzione rifiuterà l’integrazione.</p>;
    case 'non-bastano':
      return <p>Il lotto ha solo {kg(prevista.kgLiberi)} liberi: il magazzino rifiuterà la riserva.</p>;
  }
}

const TITOLI_RIFIUTO: Record<MotivoRifiuto, string> = {
  ORIGINE_DIVERSA: 'Lotto rifiutato: origine diversa',
  LOTTO_NON_DISPONIBILE: 'Lotto rifiutato: non è disponibile',
  ALTRA_BOTANICA: 'Lotto rifiutato: è di un’altra botanica',
  LOTTO_GIA_INTEGRATO: 'Lotto già integrato',
  LOTTO_DELLA_CONSEGNA: 'Lotto già contato con la consegna',
  KG_NON_VALIDI: 'Kg non validi',
  PIANO_COMPLETO: 'Il piano è già completo',
  KG_ECCESSIVI: 'Troppi kg per il piano',
  RISERVA_RIFIUTATA: 'Il magazzino non ha riservato il lotto',
};

/** Il 409 di Produzione detto per esteso: perché il lotto non entra, e cosa fare. */
function RifiutoSpiegato({
  rifiuto,
  errore,
  piano,
  onChiudi,
}: {
  rifiuto: RifiutoIntegrazione;
  errore: ErroreApi;
  piano: Piano;
  onChiudi: () => void;
}) {
  return (
    <Avviso tipo="problema" titolo={TITOLI_RIFIUTO[rifiuto.motivo]} onChiudi={onChiudi}>
      {rifiuto.motivo === 'ORIGINE_DIVERSA' ? (
        <>
          <p>
            L’edizione deve restare fedele alla sua origine. Il piano chiede {piano.botanica} da «
            {rifiuto.origineRichiesta ?? piano.origineRichiesta}», il lotto viene da «{rifiuto.origineLotto ?? 'un’altra origine'}».
          </p>
          <p>
            La regola si controlla prima di toccare il magazzino: nessun kg è stato riservato. Per i kg che mancano serve un
            lotto da «{piano.origineRichiesta}», per esempio un riordino al fornitore di quella zona.
          </p>
        </>
      ) : (
        errore.dettaglio && <p>{errore.dettaglio}</p>
      )}
    </Avviso>
  );
}
