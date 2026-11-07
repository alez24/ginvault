import { useInView, useReducedMotion } from 'motion/react';
import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router';

import { CANALI, type Canale } from '../../api/edizioni';
import {
  NOMI_STATI_PRENOTAZIONE,
  numeriBottiglie,
  soci,
  type EdizioneSoci as Edizione,
  type Ritiro as DatiRitiro,
  type Tappa,
} from '../../api/soci';
import { Avviso } from '../../componenti/Avviso';
import { Bottiglia, Cartellino } from '../../componenti/Bottiglia';
import { BOTTIGLIE_PER_DISEGNO, QuoteInBottiglie } from '../../componenti/Bottiglie';
import { DosiRicetta } from '../../componenti/Distilleria';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Divisore } from '../../componenti/Montagne';
import { Appare } from '../../componenti/Movimento';
import { PAGINE } from '../../navigazione';
import { data, giornoConNome, ilGiorno, maiuscola, numero, ora, orario, partiDelGiorno } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/**
 * Un'edizione vista da un socio (storia di Giulia): l'avviso della tiratura
 * ridotta, le sue bottiglie numerate, il ritiro e la tracciabilità.
 */
export function EdizioneSoci() {
  const { id = '' } = useParams();
  const { risultato, ricarica } = useCaricamento(() => soci.edizione(id), id);
  const [avvisoChiuso, setAvvisoChiuso] = useState(false);

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

  const edizione = risultato.dati;
  const prenotazione = edizione.prenotazione;
  // La lavanda è il colore della sola edizione d'estate
  const estiva = /estate/i.test(edizione.nome);
  const ridotta = edizione.tiratura < edizione.tiraturaAnnunciata;
  const confermata = prenotazione !== null && ['CONFERMATA', 'NUMERATA', 'RITIRATA'].includes(prenotazione.stato);

  return (
    <article className={estiva ? 'soci pagina--estate' : 'soci'} aria-labelledby="edizione-titolo">
      <title>{`${edizione.nome} · Area soci · GinVault`}</title>

      <header className="vetrina">
        <div className="contenitore vetrina__griglia">
          <div className="vetrina__testo">
            <Link className="ritorno" to={PAGINE.soci.percorso}>
              Area soci
            </Link>
            {ridotta && confermata && !avvisoChiuso && (
              <Avviso
                titolo={`La tiratura è scesa da ${numero(edizione.tiraturaAnnunciata)} a ${numero(edizione.tiratura)}, la tua prenotazione è confermata`}
                onChiudi={() => setAvvisoChiuso(true)}
              >
                <p>
                  Il raccolto di {edizione.botanica} ha dato {numero(edizione.botanicaDisponibileKg)} kg invece di{' '}
                  {numero(edizione.botanicaAnnunciataKg)}. Le bottiglie in meno escono {daDove(calati(edizione))}: la
                  quota dei soci non cambia.
                </p>
              </Avviso>
            )}
            <h1 id="edizione-titolo">{edizione.nome}</h1>
            <p className="attacco">{edizione.descrizione}</p>
            {prenotazione && (
              <dl className="vetrina__dati">
                <div>
                  <dt>Le tue bottiglie</dt>
                  <dd>
                    {prenotazione.numeri.length > 0
                      ? `${numeriBottiglie(prenotazione.numeri)} di ${numero(edizione.tiratura)}`
                      : 'Il numero arriva con l’imbottigliamento'}
                  </dd>
                </div>
                <div>
                  <dt>Prenotazione</dt>
                  <dd>
                    {NOMI_STATI_PRENOTAZIONE[prenotazione.stato]}, fatta {ilGiorno(prenotazione.prenotataIl)}
                  </dd>
                </div>
              </dl>
            )}
          </div>
          <Coppia numeri={prenotazione?.numeri ?? []} tiratura={edizione.tiratura} />
        </div>
      </header>

      {prenotazione && (
        <>
          <Divisore da="avorio" a="neve" variante={1} />
          <Ritiro ritiro={prenotazione.ritiro} numeri={prenotazione.numeri} />
          <Divisore da="neve" a="avorio" variante={2} />
        </>
      )}
      <Tracciabilita tappe={edizione.tracciabilita} botanica={edizione.botanica} />
      <Divisore da="avorio" a="neve" variante={0} />
      <Tiratura edizione={edizione} />
    </article>
  );
}

/** I canali che hanno perso bottiglie nel ricalcolo. */
function calati(edizione: Edizione): Canale[] {
  return CANALI.filter((canale) => edizione.quote[canale] < edizione.quoteAnnunciate[canale]).reverse();
}

const DA_DOVE: Record<Canale, string> = { ONLINE: 'dalla vendita online', BAR: 'dai bar', SOCI: 'dai soci' };

/** "dalla vendita online", "dalla vendita online e dai bar". */
function daDove(canali: Canale[]): string {
  return canali.map((canale) => DA_DOVE[canale]).join(' e ') || 'dalla vendita online';
}

/**
 * Le bottiglie del socio, ognuna con il cartellino del suo numero. Senza
 * prenotazione, o prima della numerazione, una bottiglia sola senza cartellino.
 */
function Coppia({ numeri, tiratura }: { numeri: number[]; tiratura: number }) {
  const bottiglie = numeri.length > 0 ? numeri.slice(0, 2) : [null];
  return (
    <Appare className="vetrina__figura">
      <figure className={`coppia coppia--${bottiglie.length}`}>
        <span className="coppia__alone" aria-hidden="true" />
        {bottiglie.map((n, indice) => (
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

/** Dalla ricetta alla bottiglia: le tappe una dopo l'altra, lungo un sentiero. */
function Tracciabilita({ tappe, botanica }: { tappe: Tappa[]; botanica: string }) {
  return (
    <section className="tracciabilita" aria-labelledby="tracciabilita-titolo">
      <div className="contenitore">
        <Appare className="tracciabilita__testa">
          <h2 id="tracciabilita-titolo">Dalla ricetta alla tua bottiglia</h2>
          <p className="attacco">
            Ogni passaggio, come lo hanno registrato la distilleria e il magazzino: la formulazione, la {botanica}{' '}
            raccolta, il controllo qualità.
          </p>
        </Appare>
        <ol className="tappe">
          {tappe.map((tappa, indice) => (
            <li key={tappa.tipo} className="tappa">
              <Appare className="tappa__corpo" ritardo={indice * 0.12}>
                <p className="tappa__quando">
                  <time dateTime={tappa.quando}>{data(tappa.quando)}</time>
                </p>
                <div className="scheda tappa__scheda">
                  <h3>{tappa.titolo}</h3>
                  <p>{tappa.testo}</p>
                  {tappa.dosi && (
                    <>
                      <p className="tappa__sottotitolo">Dosi per bottiglia</p>
                      <DosiRicetta dosi={tappa.dosi} botanicaDiStagione={botanica} />
                    </>
                  )}
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
  const differenza = edizione.tiraturaAnnunciata - edizione.tiratura;
  return (
    <section className="tiratura-soci" aria-labelledby="tiratura-titolo">
      <div className="contenitore tiratura-soci__griglia">
        <Appare className="tiratura-soci__testo">
          <h2 id="tiratura-titolo">
            {differenza > 0
              ? `Da ${numero(edizione.tiraturaAnnunciata)} a ${numero(edizione.tiratura)} bottiglie`
              : `${numero(edizione.tiratura)} bottiglie`}
          </h2>
          <p className="attacco">
            {numero(edizione.botanicaDisponibileKg)} kg di {edizione.botanica} a {numero(edizione.grammiPerBottiglia)}{' '}
            grammi per bottiglia fanno {numero(edizione.tiratura)} bottiglie
            {differenza > 0 ? `, invece delle ${numero(edizione.tiraturaAnnunciata)} annunciate.` : '.'}
          </p>
          {differenza > 0 && (
            <p>
              Le {numero(differenza)} bottiglie in meno escono {daDove(calati(edizione))}. La quota dei soci non cala
              mai: chi ha prenotato tiene le sue bottiglie.
            </p>
          )}
        </Appare>
        <div className="scheda tiratura-soci__quote">
          <QuoteCheCalano prima={edizione.quoteAnnunciate} dopo={edizione.quote} />
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
