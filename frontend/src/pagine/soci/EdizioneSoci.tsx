import { useInView, useReducedMotion } from 'motion/react';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';

import { CANALI, edizioni, type Canale, type Edizione } from '../../api/edizioni';
import { ErroreApi } from '../../api/http';
import {
  carta,
  estiva,
  fase,
  nomeEdizione,
  numeriBottiglie,
  possibilita,
  soci,
  type Possibilita,
  type Prenotazione,
  type RegoleDiVendita,
  type Ritiro as DatiRitiro,
} from '../../api/soci';
import { Avviso } from '../../componenti/Avviso';
import { Bottiglia, Cartellino } from '../../componenti/Bottiglia';
import { BOTTIGLIE_PER_DISEGNO, QuoteInBottiglie } from '../../componenti/Bottiglie';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Divisore } from '../../componenti/Montagne';
import { Appare } from '../../componenti/Movimento';
import { PAGINE } from '../../navigazione';
import { useAvvisi } from '../../sessione/Avvisi';
import {
  bottiglie,
  data,
  euro,
  giornoConNome,
  ilGiorno,
  maiuscola,
  numero,
  ora,
  orario,
  partiDelGiorno,
  traQuanto,
} from '../../utilita/numeri';
import { useAdesso } from '../../utilita/useAdesso';
import { useCaricamento } from '../../utilita/useCaricamento';

interface DatiEdizione {
  edizione: Edizione;
  /** Le mie prenotazioni di questa edizione, dalla più recente. */
  mie: Prenotazione[];
  regole: RegoleDiVendita;
}

async function caricaEdizione(id: string): Promise<DatiEdizione> {
  const [edizione, prenotazioni, regole] = await Promise.all([edizioni.trova(id), soci.prenotazioni(), soci.regole()]);
  return { edizione, mie: prenotazioni.filter((p) => p.edizione.id === id), regole };
}

/**
 * Un'edizione vista da un socio (storia di Giulia): prenota, paga con una
 * carta di prova, aspetta l'esito della SAGA e trova le sue bottiglie
 * numerate; poi il ritiro, la tracciabilità e la tiratura, con l'avviso se il
 * raccolto l'ha ridotta.
 */
export function EdizioneSoci() {
  const { id = '' } = useParams();
  const { risultato, ricarica, imposta } = useCaricamento(() => caricaEdizione(id), id);
  const { alNuovoAvviso } = useAvvisi();
  const adesso = useAdesso(5_000);

  // Rilegge senza far sparire la pagina: dopo una POST e a ogni avviso in
  // diretta di questa edizione
  const aggiorna = useCallback(() => void caricaEdizione(id).then(imposta, () => undefined), [id, imposta]);
  useEffect(
    () =>
      alNuovoAvviso((notifica) => {
        if (notifica.edizioneId === id) aggiorna();
      }),
    [alNuovoAvviso, aggiorna, id],
  );

  // Mentre si aspetta l'esito del pagamento (o l'annullamento dopo la scadenza)
  // rilegge anche da sola, se l'avviso non arriva: ogni 2 secondi nel primo
  // minuto, poi ogni 10 (Pagamenti fermo). Un giro finisce prima del successivo.
  const inAttesa =
    risultato.stato === 'pronto'
      ? risultato.dati.mie.find((p) => ['IN_PAGAMENTO', 'TEMPO_FINITO'].includes(fase(p, adesso)))
      : undefined;
  const idInAttesa = inAttesa?.id;
  const daQuando = inAttesa ? (inAttesa.pagamento?.richiestoIl ?? inAttesa.scadeIl) : undefined;
  useEffect(() => {
    if (!idInAttesa || !daQuando) return;
    let finito = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const giro = () => {
      const passati = Date.now() - Date.parse(daQuando);
      timer = setTimeout(() => {
        caricaEdizione(id)
          .then((dati) => {
            if (!finito) imposta(dati);
          })
          .catch(() => undefined)
          .finally(() => {
            if (!finito) giro();
          });
      }, passati < 60_000 ? 2_000 : 10_000);
    };
    giro();
    return () => {
      finito = true;
      clearTimeout(timer);
    };
  }, [id, idInAttesa, daQuando, imposta]);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico l’edizione…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovata = risultato.errore.stato === 404;
    return (
      <section className="contenitore soci-mancante" aria-labelledby="edizione-titolo">
        <title>Edizione · Area soci · GinVault</title>
        <Link className="ritorno" to={PAGINE.soci.percorso}>
          Area soci
        </Link>
        <h1 id="edizione-titolo">{nonTrovata ? 'Edizione non trovata' : 'L’edizione non si apre'}</h1>
        {nonTrovata ? (
          <p className="attacco">
            L’indirizzo non corrisponde a nessuna edizione del club. Le tue edizioni sono nell’area soci.
          </p>
        ) : (
          <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
        )}
      </section>
    );
  }

  const { edizione, mie, regole } = risultato.dati;
  const nome = nomeEdizione(edizione);
  const confermate = mie.filter((p) => p.stato === 'CONFERMATA');
  const numeri = confermate.flatMap((p) => p.numeriBottiglia).sort((a, b) => a - b);

  /** Una prenotazione nuova o pagata: la si mette al posto suo, poi si rilegge tutto. */
  const conPrenotazione = (prenotazione: Prenotazione) => {
    imposta({ ...risultato.dati, mie: [prenotazione, ...mie.filter((p) => p.id !== prenotazione.id)] });
    aggiorna();
  };

  return (
    <article className={estiva(nome) ? 'soci pagina--estate' : 'soci'} aria-labelledby="edizione-titolo">
      <title>{`${nome} · Area soci · GinVault`}</title>
      <Vetrina edizione={edizione} nome={nome} numeri={numeri} attiva={mie.some((p) => p.stato === 'IN_ATTESA_PAGAMENTO')} />

      <Divisore da="avorio" a="neve" variante={1} />
      <section className="azione-soci" aria-label="La tua prenotazione">
        <div className="contenitore azione-soci__griglia">
          <LaTuaPrenotazione
            edizione={edizione}
            mie={mie}
            regole={regole}
            adesso={adesso}
            onPrenotazione={conPrenotazione}
          />
        </div>
      </section>

      {confermate.length > 0 && <Ritiro ritiro={confermate[0].ritiro} numeri={numeri} />}
      <Divisore da="neve" a="avorio" variante={2} />
      <Tracciabilita edizione={edizione} />
      <Divisore da="avorio" a="neve" variante={0} />
      <Tiratura edizione={edizione} />
    </article>
  );
}

// ---- in alto: l'edizione e le mie bottiglie -----------------------------------

function Vetrina({ edizione, nome, numeri, attiva }: { edizione: Edizione; nome: string; numeri: number[]; attiva: boolean }) {
  const [avvisoChiuso, setAvvisoChiuso] = useState(false);
  const annunciata = edizione.tiraturaAnnunciata ?? edizione.tiratura;
  const ridotta = edizione.tiratura < annunciata;
  const botanica = edizione.botanicaDiStagione;
  return (
    <header className="vetrina">
      <div className="contenitore vetrina__griglia">
        <div className="vetrina__testo">
          <Link className="ritorno" to={PAGINE.soci.percorso}>
            Area soci
          </Link>
          {ridotta && numeri.length > 0 && !avvisoChiuso && (
            <Avviso
              tipo="attenzione"
              titolo={`La tiratura è scesa da ${numero(annunciata)} a ${numero(edizione.tiratura)}, le tue bottiglie restano tue`}
              onChiudi={() => setAvvisoChiuso(true)}
            >
              <p>
                {edizione.botanicaAnnunciataKg !== null &&
                  `Il raccolto di ${botanica} ha dato ${numero(edizione.botanicaDisponibileKg)} kg invece di ${numero(edizione.botanicaAnnunciataKg)}. `}
                Le bottiglie in meno escono {daDove(calati(edizione))}: la quota dei soci non cambia.
              </p>
            </Avviso>
          )}
          <h1 id="edizione-titolo">{nome}</h1>
          <p className="attacco">
            Ginepro e {botanica}: {numero(edizione.grammiPerBottiglia)} grammi di {botanica} in ogni bottiglia.
            {edizione.origineBotanica && ` ${maiuscola(botanica)} da: ${edizione.origineBotanica}.`}
          </p>
          <dl className="vetrina__dati">
            <div>
              <dt>Le tue bottiglie</dt>
              <dd>
                {numeri.length > 0
                  ? `${numeriBottiglie(numeri)} di ${numero(edizione.tiratura)}`
                  : attiva
                    ? 'Il numero arriva con il pagamento'
                    : 'Non ne hai ancora prenotate'}
              </dd>
            </div>
            <div>
              <dt>Vendita</dt>
              <dd>{faseDiVendita(edizione)}</dd>
            </div>
          </dl>
        </div>
        <Coppia numeri={numeri} tiratura={edizione.tiratura} />
      </div>
    </header>
  );
}

function faseDiVendita(edizione: Edizione): string {
  switch (edizione.stato) {
    case 'IN_PRELAZIONE':
      return edizione.prelazioneFinoAl ? `Prelazione dei soci fino al ${data(edizione.prelazioneFinoAl)}` : 'Prelazione dei soci';
    case 'IN_VENDITA':
      return 'In vendita online';
    case 'ESAURITA':
      return 'Esaurita';
  }
}

/** I canali che hanno perso bottiglie nel ricalcolo. */
function calati(edizione: Edizione): Canale[] {
  const annunciate = edizione.quoteAnnunciate ?? edizione.quote;
  return CANALI.filter((canale) => edizione.quote[canale] < annunciate[canale]).reverse();
}

const DA_DOVE: Record<Canale, string> = { ONLINE: 'dalla vendita online', BAR: 'dai bar', SOCI: 'dai soci' };

/** "dalla vendita online", "dalla vendita online e dai bar". */
function daDove(canali: Canale[]): string {
  return canali.map((canale) => DA_DOVE[canale]).join(' e ') || 'dalla vendita online';
}

/**
 * Le bottiglie del socio, ognuna con il cartellino del suo numero. Senza
 * bottiglie confermate, una bottiglia sola senza cartellino.
 */
function Coppia({ numeri, tiratura }: { numeri: number[]; tiratura: number }) {
  const bottiglieDisegnate = numeri.length > 0 ? numeri.slice(0, 2) : [null];
  return (
    <Appare className="vetrina__figura">
      <figure className={`coppia coppia--${bottiglieDisegnate.length}`}>
        <span className="coppia__alone" aria-hidden="true" />
        {bottiglieDisegnate.map((n, indice) => (
          <div key={n ?? indice} className="coppia__bottiglia">
            <Bottiglia subito={indice === 0} decorativa={indice > 0} sizes="(min-width: 48rem) 9rem, 5rem" />
            {n !== null && (
              <Cartellino
                className={indice === 1 ? 'cartellino--destra' : undefined}
                edizione={`n. ${n}`}
                sotto={`di ${numero(tiratura)}`}
              />
            )}
          </div>
        ))}
      </figure>
    </Appare>
  );
}

// ---- prenotare e pagare ---------------------------------------------------------------

interface PropsPrenotazione {
  edizione: Edizione;
  mie: Prenotazione[];
  regole: RegoleDiVendita;
  adesso: Date;
  onPrenotazione: (prenotazione: Prenotazione) => void;
}

/**
 * Cosa può fare il socio adesso: pagare la prenotazione in attesa, aspettare
 * l'esito, oppure prenotare. Sopra, com'è andata l'ultima prenotazione.
 */
function LaTuaPrenotazione({ edizione, mie, regole, adesso, onPrenotazione }: PropsPrenotazione) {
  const attiva = mie.find((p) => p.stato === 'IN_ATTESA_PAGAMENTO');
  const ultima = mie[0];
  const puoi = possibilita(edizione, regole, mie);

  return (
    <>
      {ultima && !attiva && <EsitoUltima prenotazione={ultima} adesso={adesso} />}
      {attiva && fase(attiva, adesso) === 'DA_PAGARE' && (
        <ModuloPagamento prenotazione={attiva} metodi={regole.metodiDiPagamento} adesso={adesso} onPagamento={onPrenotazione} />
      )}
      {attiva && fase(attiva, adesso) === 'IN_PAGAMENTO' && <PagamentoInCorso prenotazione={attiva} />}
      {attiva && fase(attiva, adesso) === 'TEMPO_FINITO' && (
        <div className="scheda azione-soci__scheda" role="status">
          <h2>Il tempo per pagare è finito</h2>
          <p>
            La prenotazione si annulla tra pochi secondi e le {bottiglie(attiva.bottiglie)} tornano disponibili. Dopo puoi
            prenotare di nuovo, se ce ne sono ancora.
          </p>
        </div>
      )}
      {!attiva && puoi.ancora > 0 && (
        <ModuloPrenotazione edizione={edizione} puoi={puoi} regole={regole} onPrenotata={onPrenotazione} />
      )}
      {!attiva && puoi.ancora === 0 && <NonPrenotabile edizione={edizione} puoi={puoi} />}
    </>
  );
}

const RECENTE_MS = 15 * 60_000;

/** L'ultima prenotazione, se è appena finita: confermata con i numeri, o annullata con il perché. */
function EsitoUltima({ prenotazione: p, adesso }: { prenotazione: Prenotazione; adesso: Date }) {
  const [chiuso, setChiuso] = useState(false);
  if (chiuso) {
    return null;
  }
  // Solo subito dopo com'è finita: più tardi i numeri sono in alto e nel
  // ritiro, e gli annullamenti restano negli avvisi del club
  const finitaIl = p.stato === 'CONFERMATA' ? p.pagamento?.confermataIl : p.annullamento?.annullataIl;
  if (!finitaIl || adesso.getTime() - Date.parse(finitaIl) > RECENTE_MS) {
    return null;
  }
  if (p.stato === 'CONFERMATA') {
    return (
      <Avviso titolo="Pagamento riuscito" onChiudi={() => setChiuso(true)}>
        <p>
          Hai pagato {euro(p.totale)} per {bottiglie(p.bottiglie)}.{' '}
          {p.numeriBottiglia.length === 1 ? 'La tua bottiglia è la' : 'Le tue bottiglie sono la'}{' '}
          {p.numeriBottiglia.join(' e la ')}.
        </p>
      </Avviso>
    );
  }
  if (p.stato === 'ANNULLATA' && p.annullamento) {
    return (
      <Avviso
        tipo="attenzione"
        titolo={p.annullamento.causa === 'SCADUTA' ? 'Prenotazione scaduta' : 'Pagamento non riuscito'}
        onChiudi={() => setChiuso(true)}
      >
        <p>
          {p.annullamento.spiegazione ??
            (p.annullamento.causa === 'SCADUTA'
              ? 'Il tempo per pagare è finito: le bottiglie sono tornate disponibili.'
              : 'La carta è stata rifiutata: le bottiglie sono tornate disponibili.')}
        </p>
        {p.rimborso && (
          <p>
            {p.rimborso.eseguitoIl
              ? `Il pagamento era arrivato tardi: ti abbiamo restituito ${euro(p.totale)} ${ilGiorno(p.rimborso.eseguitoIl)}.`
              : `Il pagamento è arrivato dopo la scadenza: ti restituiamo ${euro(p.totale)}.`}
          </p>
        )}
      </Avviso>
    );
  }
  return null;
}

function ModuloPrenotazione({
  edizione,
  puoi,
  regole,
  onPrenotata,
}: {
  edizione: Edizione;
  puoi: Possibilita;
  regole: RegoleDiVendita;
  onPrenotata: (prenotazione: Prenotazione) => void;
}) {
  const [quante, setQuante] = useState(puoi.ancora);
  const [inCorso, setInCorso] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);
  const scelta = Math.min(quante, puoi.ancora);
  const scelte = Array.from({ length: puoi.ancora }, (_, indice) => indice + 1);

  const invia = (evento: FormEvent) => {
    evento.preventDefault();
    setInCorso(true);
    setErrore(null);
    soci.prenota(edizione.id, scelta).then(onPrenotata, (e: unknown) => {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto', String(e)));
      setInCorso(false);
    });
  };

  return (
    <form className="modulo scheda azione-soci__scheda" onSubmit={invia} aria-labelledby="prenota-titolo">
      <h2 id="prenota-titolo">Prenota le tue bottiglie</h2>
      <p>
        {edizione.stato === 'IN_PRELAZIONE'
          ? `Sei in prelazione: puoi prenotarne fino a ${bottiglie(puoi.massimo)}, a ${euro(puoi.prezzo)} l’una.`
          : `La vendita online è aperta: puoi prenotarne fino a ${bottiglie(puoi.massimo)}, a ${euro(puoi.prezzo)} l’una.`}
        {puoi.giaPrenotate > 0 && ` Ne hai già ${numero(puoi.giaPrenotate)}.`}
      </p>
      <fieldset>
        <legend>Quante bottiglie</legend>
        <div className="scelte scelte--in-fila">
          {scelte.map((n) => (
            <label key={n} className="scelta">
              <input type="radio" name="quante" value={n} checked={scelta === n} onChange={() => setQuante(n)} />
              <span>
                <span className="scelta__nome">{bottiglie(n)}</span>
                <span className="scelta__aiuto">{euro(n * puoi.prezzo)}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="campo__aiuto">
        Dopo hai {numero(regole.minutiPerPagare)} minuti per pagare: intanto le bottiglie sono tenute per te. Il numero di
        ogni bottiglia arriva con il pagamento.
      </p>
      {errore && <ErroreOperazione errore={errore} onChiudi={() => setErrore(null)} />}
      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={inCorso}>
          {inCorso ? 'Prenoto…' : `Prenota ${bottiglie(scelta)}`}
        </button>
      </div>
    </form>
  );
}

function ModuloPagamento({
  prenotazione: p,
  metodi,
  adesso,
  onPagamento,
}: {
  prenotazione: Prenotazione;
  metodi: string[];
  adesso: Date;
  onPagamento: (prenotazione: Prenotazione) => void;
}) {
  const [metodo, setMetodo] = useState(metodi[0] ?? '');
  const [inCorso, setInCorso] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);

  const invia = (evento: FormEvent) => {
    evento.preventDefault();
    setInCorso(true);
    setErrore(null);
    soci.paga(p.id, metodo).then(onPagamento, (e: unknown) => {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto', String(e)));
      setInCorso(false);
    });
  };

  return (
    <form className="modulo scheda azione-soci__scheda" onSubmit={invia} aria-labelledby="paga-titolo">
      <h2 id="paga-titolo">Paga la prenotazione</h2>
      <p>
        {maiuscola(bottiglie(p.bottiglie))} a {euro(p.prezzoPerBottiglia)}: {euro(p.totale)}. Da pagare entro le{' '}
        {ora(p.scadeIl)}, {traQuanto(p.scadeIl, adesso)}.
      </p>
      <fieldset>
        <legend>Carta di prova</legend>
        <p className="campo__aiuto spazio-sotto">Stripe è in modalità test: nessun addebito vero.</p>
        <div className="scelte">
          {metodi.map((m) => (
            <label key={m} className="scelta">
              <input type="radio" name="metodo" value={m} checked={metodo === m} onChange={() => setMetodo(m)} />
              <span>
                <span className="scelta__nome">{carta(m).nome}</span>
                <span className="scelta__aiuto">{carta(m).aiuto}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>
      {errore && <ErroreOperazione errore={errore} onChiudi={() => setErrore(null)} />}
      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={inCorso || !metodo}>
          {inCorso ? 'Invio il pagamento…' : `Paga ${euro(p.totale)}`}
        </button>
      </div>
    </form>
  );
}

/** Il pagamento è partito (202): l'esito lo porta la SAGA, qui e negli avvisi. */
function PagamentoInCorso({ prenotazione: p }: { prenotazione: Prenotazione }) {
  return (
    <div className="scheda azione-soci__scheda pagamento-in-corso" role="status">
      <h2>Pagamento in corso</h2>
      <p>
        {euro(p.totale)} con la carta «{carta(p.pagamento?.metodo ?? '').nome}». Stripe di solito risponde in pochi
        secondi: l’esito compare qui e negli avvisi, anche se chiudi la pagina.
      </p>
    </div>
  );
}

function NonPrenotabile({ edizione, puoi }: { edizione: Edizione; puoi: Possibilita }) {
  let testo: string;
  if (puoi.motivo === 'LIMITE') {
    testo = `Hai già prenotato ${bottiglie(puoi.giaPrenotate)}: è il massimo per persona ${
      edizione.stato === 'IN_PRELAZIONE' ? 'in prelazione' : 'nella vendita online'
    }.`;
  } else if (edizione.stato === 'IN_PRELAZIONE') {
    testo = 'Le bottiglie della quota dei soci sono tutte prenotate. Alla fine della prelazione si apre la vendita online.';
  } else {
    testo = 'Le bottiglie in vendita sono tutte prenotate. Se una prenotazione non viene pagata, tornano disponibili.';
  }
  return <p className="azione-soci__nota">{testo}</p>;
}

// ---- il ritiro, la tracciabilità, la tiratura ------------------------------------------

/** Il ritiro delle bottiglie: un foglio di calendario e i dettagli dell'appuntamento. */
function Ritiro({ ritiro, numeri }: { ritiro: DatiRitiro; numeri: number[] }) {
  const giorno = partiDelGiorno(ritiro.inizio);
  return (
    <section className="ritiro" aria-labelledby="ritiro-titolo">
      <div className="contenitore ritiro__griglia">
        <Appare className="ritiro__foglio">
          {/* Le stesse informazioni sono scritte per esteso qui accanto */}
          <p className="foglio" aria-hidden="true">
            <span className="foglio__settimana">{giorno.settimana}</span>
            <span className="foglio__giorno">{giorno.giorno}</span>
            <span className="foglio__mese">{giorno.mese}</span>
            <span className="foglio__ora">ore {ora(ritiro.inizio)}</span>
          </p>
        </Appare>
        <Appare className="ritiro__testo" ritardo={0.1}>
          <h2 id="ritiro-titolo">Ritiro alla {ritiro.occasione}</h2>
          <p className="ritiro__tema">{ritiro.tema}</p>
          <p>{ritiro.descrizione}</p>
          <dl className="dati ritiro__dati">
            <dt>Quando</dt>
            <dd>
              {maiuscola(giornoConNome(ritiro.inizio))}, {orario(ritiro.inizio, ritiro.fine)}
            </dd>
            <dt>Dove</dt>
            <dd>{ritiro.luogo}</dd>
            {numeri.length > 0 && (
              <>
                <dt>Ritiri</dt>
                <dd>Le bottiglie {numeriBottiglie(numeri)}</dd>
              </>
            )}
            <dt>Da portare</dt>
            <dd>{ritiro.daPortare}</dd>
          </dl>
        </Appare>
      </div>
    </section>
  );
}

interface Tappa {
  chiave: string;
  titolo: string;
  quando?: string;
  testo: string;
  dati: { etichetta: string; valore: string }[];
}

/** Le tappe che Edizioni conosce: la ricetta scelta dal panel e la botanica di stagione. */
function tappeDellEdizione(edizione: Edizione): Tappa[] {
  const botanica = edizione.botanicaDiStagione;
  const annunciati = edizione.botanicaAnnunciataKg ?? edizione.botanicaDisponibileKg;
  const annunciata = edizione.tiraturaAnnunciata ?? edizione.tiratura;
  const meno = edizione.botanicaDisponibileKg < annunciati;
  return [
    {
      chiave: 'ricetta',
      titolo: 'La ricetta',
      quando: edizione.creataIl,
      testo: `Il distillatore ha annunciato l’edizione con la versione ${edizione.formulazione.versione} della ricetta, quella scelta dal panel d’assaggio. Per ${numero(annunciata)} bottiglie servivano ${numero(annunciati)} kg di ${botanica}.`,
      dati: [
        { etichetta: 'Versione della ricetta', valore: String(edizione.formulazione.versione) },
        { etichetta: `${maiuscola(botanica)} per bottiglia`, valore: `${numero(edizione.grammiPerBottiglia)} g` },
      ],
    },
    {
      chiave: 'botanica',
      titolo: `La ${botanica}`,
      testo: meno
        ? `Il raccolto ha dato meno del previsto: ${numero(edizione.botanicaDisponibileKg)} kg invece di ${numero(annunciati)}, abbastanza per ${bottiglie(edizione.tiratura)}.`
        : `Ci sono tutti i ${numero(edizione.botanicaDisponibileKg)} kg previsti: abbastanza per ${bottiglie(edizione.tiratura)}.`,
      dati: [
        ...(edizione.origineBotanica ? [{ etichetta: 'Provenienza', valore: edizione.origineBotanica }] : []),
        { etichetta: 'Disponibili', valore: `${numero(edizione.botanicaDisponibileKg)} kg su ${numero(annunciati)} previsti` },
      ],
    },
  ];
}

/** Dalla ricetta alla bottiglia: le tappe una dopo l'altra, lungo un sentiero. */
function Tracciabilita({ edizione }: { edizione: Edizione }) {
  const tappe = tappeDellEdizione(edizione);
  return (
    <section className="tracciabilita" aria-labelledby="tracciabilita-titolo">
      <div className="contenitore">
        <Appare className="tracciabilita__testa">
          <h2 id="tracciabilita-titolo">Dalla ricetta alla tua bottiglia</h2>
          <p className="attacco">
            Ogni passaggio, come lo ha registrato la distilleria: la ricetta e la {edizione.botanicaDiStagione} di stagione.
          </p>
        </Appare>
        <ol className="tappe">
          {tappe.map((tappa, indice) => (
            <li key={tappa.chiave} className="tappa">
              <Appare className="tappa__corpo" ritardo={indice * 0.12}>
                <p className="tappa__quando">{tappa.quando && <time dateTime={tappa.quando}>{data(tappa.quando)}</time>}</p>
                <div className="scheda tappa__scheda">
                  <h3>{tappa.titolo}</h3>
                  <p>{tappa.testo}</p>
                  <dl className="tappa__dati">
                    {tappa.dati.map((dato) => (
                      <div key={dato.etichetta}>
                        <dt>{dato.etichetta}</dt>
                        <dd>{dato.valore}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              </Appare>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

/** La tiratura annunciata e quella vera: le bottiglie in meno si svuotano quando arrivano sullo schermo. */
function Tiratura({ edizione }: { edizione: Edizione }) {
  const annunciata = edizione.tiraturaAnnunciata ?? edizione.tiratura;
  const differenza = annunciata - edizione.tiratura;
  return (
    <section className="tiratura-soci" aria-labelledby="tiratura-titolo">
      <div className="contenitore tiratura-soci__griglia">
        <Appare className="tiratura-soci__testo">
          <h2 id="tiratura-titolo">
            {differenza > 0
              ? `Da ${numero(annunciata)} a ${numero(edizione.tiratura)} bottiglie`
              : `${numero(edizione.tiratura)} bottiglie`}
          </h2>
          <p className="attacco">
            {numero(edizione.botanicaDisponibileKg)} kg di {edizione.botanicaDiStagione} a{' '}
            {numero(edizione.grammiPerBottiglia)} grammi per bottiglia fanno {numero(edizione.tiratura)} bottiglie
            {differenza > 0 ? `, invece delle ${numero(annunciata)} annunciate.` : '.'}
          </p>
          {differenza > 0 && (
            <p>
              Le {numero(differenza)} bottiglie in meno escono {daDove(calati(edizione))}. La quota dei soci non cala
              mai: chi ha prenotato tiene le sue bottiglie.
            </p>
          )}
        </Appare>
        <div className="scheda tiratura-soci__quote">
          <QuoteCheCalano prima={edizione.quoteAnnunciate ?? edizione.quote} dopo={edizione.quote} />
          <p className="nota">Ogni bottiglia disegnata ne vale {BOTTIGLIE_PER_DISEGNO}.</p>
        </div>
      </div>
    </section>
  );
}

/**
 * Le quote partono da quelle annunciate e calano quando arrivano sullo schermo:
 * le bottiglie tolte si svuotano una per una. Con meno movimento si vede subito il dopo.
 */
function QuoteCheCalano({ prima, dopo }: { prima: Record<Canale, number>; dopo: Record<Canale, number> }) {
  const riquadro = useRef<HTMLDivElement>(null);
  const inVista = useInView(riquadro, { once: true, amount: 0.6 });
  const fermo = useReducedMotion();
  return (
    <div ref={riquadro}>
      {inVista || fermo ? (
        <QuoteInBottiglie key="dopo" quote={dopo} prima={prima} />
      ) : (
        <QuoteInBottiglie key="prima" quote={prima} />
      )}
    </div>
  );
}
