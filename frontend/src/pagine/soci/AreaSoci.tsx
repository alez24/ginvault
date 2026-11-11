import { useEffect } from 'react';
import { Link } from 'react-router';

import { edizioni, type Canale, type Edizione } from '../../api/edizioni';
import { tonoAvviso } from '../../api/notifiche';
import {
  estiva,
  fase,
  NOMI_STATI_PRENOTAZIONE,
  nomeEdizione,
  numeriBottiglie,
  percorsoEdizioneSoci,
  possibilita,
  soci,
  type Prenotazione,
  type RegoleDiVendita,
} from '../../api/soci';
import { PAESAGGIO } from '../../assets/foto/foto';
import { Avviso } from '../../componenti/Avviso';
import { Bottiglia } from '../../componenti/Bottiglia';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Divisore } from '../../componenti/Montagne';
import { Appare } from '../../componenti/Movimento';
import { useAvvisi } from '../../sessione/Avvisi';
import { useSessione } from '../../sessione/Sessione';
import { bottiglie, data, euro, giornoConNome, giorni, ilGiorno, numero, ora, traQuanto } from '../../utilita/numeri';
import { useAdesso } from '../../utilita/useAdesso';
import { useCaricamento } from '../../utilita/useCaricamento';

interface DatiArea {
  prenotazioni: Prenotazione[];
  aperte: Edizione[];
  regole: RegoleDiVendita;
}

/** Le mie prenotazioni, le edizioni che si possono ancora prenotare e le regole del club, da Edizioni. */
async function caricaArea(): Promise<DatiArea> {
  const [prenotazioni, tutte, regole] = await Promise.all([soci.prenotazioni(), edizioni.elenco(), soci.regole()]);
  const aperte = tutte.filter((edizione) => possibilita(edizione, regole, prenotazioni).ancora > 0);
  return { prenotazioni, aperte, regole };
}

/**
 * Area soci (storia di Giulia): gli avvisi del club in diretta (Notifiche), le
 * prenotazioni e le edizioni aperte (Edizioni), le regole della prelazione.
 */
export function AreaSoci() {
  const { utente } = useSessione();
  const avvisi = useAvvisi();
  const { risultato, ricarica, imposta } = useCaricamento(caricaArea, 'area-soci');
  const nome = utente?.nome?.split(' ')[0];
  const daLeggere = avvisi.notifiche.filter((notifica) => !notifica.lettaIl);

  // Un avviso in diretta vuol dire che qualcosa è cambiato (una conferma, una
  // tiratura): si rileggono i dati senza far sparire la pagina
  const { alNuovoAvviso } = avvisi;
  useEffect(() => alNuovoAvviso(() => void caricaArea().then(imposta, () => undefined)), [alNuovoAvviso, imposta]);

  return (
    <section className="soci" aria-labelledby="soci-titolo">
      <title>Area soci · GinVault</title>
      <header className="paesaggio">
        <picture className="paesaggio__foto">
          <source media="(min-width: 48rem)" srcSet={PAESAGGIO.larga.srcSet} sizes="100vw" />
          <img src={PAESAGGIO.alta.src} srcSet={PAESAGGIO.alta.srcSet} sizes="100vw" alt="" />
        </picture>
        <div className="contenitore paesaggio__testo">
          <h1 id="soci-titolo">Area soci</h1>
          <p className="attacco">
            {nome ? `Ciao ${nome}: qui` : 'Qui'} trovi le tue prenotazioni, gli avvisi del club e le edizioni che puoi
            ancora prenotare.
          </p>
        </div>
        <Divisore da="trasparente" a="neve" variante={2} />
      </header>

      <div className="contenitore soci__corpo">
        <div className="soci__avvisi" aria-label="Avvisi del club" aria-live="polite">
          {daLeggere.map((notifica) => (
            <Avviso
              key={notifica.id}
              tipo={tonoAvviso(notifica.tipo)}
              titolo={notifica.titolo}
              onChiudi={() => avvisi.segnaLetta(notifica.id)}
            >
              <p>{notifica.testo}</p>
              <p className="avviso__piede">
                Arrivato {ilGiorno(notifica.creataIl)} alle {ora(notifica.creataIl)}.
                {notifica.edizioneId && (
                  <>
                    {' '}
                    <Link to={percorsoEdizioneSoci(notifica.edizioneId)}>Apri l’edizione</Link>
                  </>
                )}
              </p>
            </Avviso>
          ))}
          {daLeggere.length > 1 && (
            <p className="soci__comandi-avvisi">
              <button type="button" className="pulsante-testo" onClick={avvisi.segnaTutteLette}>
                Segna tutti come letti
              </button>
            </p>
          )}
          {avvisi.collegamento === 'scollegato' && (
            <p className="nota">Gli avvisi in diretta non arrivano: provo a ricollegarmi.</p>
          )}
        </div>

        {risultato.stato === 'caricamento' && <p className="attesa">Carico le tue prenotazioni…</p>}
        {risultato.stato === 'errore' && <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />}
        {risultato.stato === 'pronto' && (
          <>
            <section className="soci__sezione" aria-labelledby="prenotazioni-titolo">
              <h2 id="prenotazioni-titolo">Le tue prenotazioni</h2>
              {risultato.dati.prenotazioni.length === 0 ? (
                <p className="vuoto">
                  Non hai ancora prenotazioni. Quando un’edizione viene annunciata hai{' '}
                  {giorni(risultato.dati.regole.giorniDiPrelazione)} per prenotare, prima di tutti.
                </p>
              ) : (
                <ul className="prenotazioni">
                  {risultato.dati.prenotazioni.map((prenotazione) => (
                    <SchedaPrenotazione key={prenotazione.id} prenotazione={prenotazione} />
                  ))}
                </ul>
              )}
            </section>

            {risultato.dati.aperte.length > 0 && (
              <section className="soci__sezione soci__sezione--aperte" aria-labelledby="aperte-titolo">
                <h2 id="aperte-titolo">Da prenotare</h2>
                <ul className="prenotazioni">
                  {risultato.dati.aperte.map((edizione) => (
                    <SchedaEdizioneAperta
                      key={edizione.id}
                      edizione={edizione}
                      regole={risultato.dati.regole}
                      prenotazioni={risultato.dati.prenotazioni}
                    />
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </div>

      {risultato.stato === 'pronto' && <Regole regole={risultato.dati.regole} />}
    </section>
  );
}

/** Una prenotazione: l'edizione, a che punto è, i numeri e il ritiro. Tutta la scheda porta all'edizione. */
function SchedaPrenotazione({ prenotazione: p }: { prenotazione: Prenotazione }) {
  const adesso = useAdesso();
  const nome = nomeEdizione(p.edizione);
  const aPunto = fase(p, adesso);
  let riga: string;
  switch (aPunto) {
    case 'CONFERMATA':
      riga = `Bottiglie ${numeriBottiglie(p.numeriBottiglia)} di ${numero(p.edizione.tiratura)}`;
      break;
    case 'DA_PAGARE':
      riga = `${bottiglie(p.bottiglie)} da pagare entro le ${ora(p.scadeIl)}, ${traQuanto(p.scadeIl, adesso)}`;
      break;
    case 'IN_PAGAMENTO':
      riga = `Pagamento in corso: ${euro(p.totale)}`;
      break;
    case 'TEMPO_FINITO':
      riga = 'Il tempo per pagare è finito';
      break;
    case 'ANNULLATA':
      riga = p.annullamento?.causa === 'SCADUTA' ? 'Il tempo per pagare è finito' : 'Il pagamento non è riuscito';
      break;
  }
  const classi = ['carta', 'prenotazione', estiva(nome) && 'prenotazione--estate', p.stato === 'ANNULLATA' && 'prenotazione--annullata']
    .filter(Boolean)
    .join(' ');
  return (
    <li className={classi}>
      <div className="prenotazione__figura" aria-hidden="true">
        <Bottiglia decorativa sizes="3rem" />
      </div>
      <div className="prenotazione__testo">
        <p className={`stato stato--${p.stato.toLowerCase()}`}>
          {aPunto === 'IN_PAGAMENTO' ? 'Pagamento in corso' : NOMI_STATI_PRENOTAZIONE[p.stato]}
        </p>
        <h3 className="carta__titolo">
          <Link className="carta__collegamento" to={percorsoEdizioneSoci(p.edizione.id)}>
            {nome}
          </Link>
        </h3>
        <p className="prenotazione__numeri">{riga}</p>
        <dl className="prenotazione__dati">
          {p.stato === 'CONFERMATA' && (
            <div>
              <dt>Ritiro</dt>
              <dd>
                Alla {p.ritiro.occasione}, {giornoConNome(p.ritiro.inizio)} alle {ora(p.ritiro.inizio)}
              </dd>
            </div>
          )}
          <div>
            <dt>Prenotata</dt>
            <dd>
              {data(p.prenotataIl)}, {bottiglie(p.bottiglie)} a {euro(p.prezzoPerBottiglia)}
            </dd>
          </div>
          {p.rimborso && (
            <div>
              <dt>Rimborso</dt>
              <dd>
                {p.rimborso.eseguitoIl
                  ? `${euro(p.totale)} restituiti ${ilGiorno(p.rimborso.eseguitoIl)}`
                  : 'Il pagamento è arrivato tardi: lo stiamo restituendo'}
              </dd>
            </div>
          )}
        </dl>
      </div>
    </li>
  );
}

/** Si legge dopo «nella quota». */
const QUOTA: Record<Canale, string> = { SOCI: 'dei soci', BAR: 'dei bar', ONLINE: 'online' };

/** Un'edizione che si può ancora prenotare: quante bottiglie, a che prezzo, fino a quando. */
function SchedaEdizioneAperta({
  edizione,
  regole,
  prenotazioni,
}: {
  edizione: Edizione;
  regole: RegoleDiVendita;
  prenotazioni: Prenotazione[];
}) {
  const nome = nomeEdizione(edizione);
  const puoi = possibilita(edizione, regole, prenotazioni);
  return (
    <li className={estiva(nome) ? 'carta prenotazione prenotazione--estate' : 'carta prenotazione'}>
      <div className="prenotazione__figura" aria-hidden="true">
        <Bottiglia decorativa sizes="3rem" />
      </div>
      <div className="prenotazione__testo">
        <p className="stato stato--aperta">
          {edizione.stato === 'IN_PRELAZIONE' && edizione.prelazioneFinoAl
            ? `In prelazione fino al ${data(edizione.prelazioneFinoAl)}`
            : 'In vendita'}
        </p>
        <h3 className="carta__titolo">
          <Link className="carta__collegamento" to={percorsoEdizioneSoci(edizione.id)}>
            {nome}
          </Link>
        </h3>
        <p className="prenotazione__numeri">
          Puoi prenotarne fino a {numero(puoi.ancora)}, a {euro(puoi.prezzo)} l’una
        </p>
        <dl className="prenotazione__dati">
          <div>
            <dt>Libere</dt>
            <dd>
              {bottiglie(puoi.libere)} nella quota {QUOTA[puoi.canale]}, su {numero(edizione.tiratura)}
            </dd>
          </div>
        </dl>
      </div>
    </li>
  );
}

function Regole({ regole }: { regole: RegoleDiVendita }) {
  return (
    <>
      <Divisore da="neve" a="avorio" variante={3} />
      <section className="regole fondo-avorio" aria-labelledby="regole-titolo">
        <div className="contenitore">
          <h2 id="regole-titolo">Le regole del club</h2>
          <ul className="regole__elenco">
            <li>
              <Appare>
                <h3>{giorni(regole.giorniDiPrelazione)} di prelazione</h3>
                <p>
                  Quando un’edizione viene annunciata puoi prenotare prima dei bar e della vendita online, a{' '}
                  {euro(regole.prezzoSoci)} la bottiglia invece di {euro(regole.prezzoOnline)}.
                </p>
              </Appare>
            </li>
            <li>
              <Appare ritardo={0.1}>
                <h3>Al massimo {bottiglie(regole.bottigliePerSocio)}</h3>
                <p>
                  Per ogni edizione, ognuna con il suo numero sul cartellino. Il numero arriva quando il pagamento è
                  riuscito, e hai {numero(regole.minutiPerPagare)} minuti per pagare.
                </p>
              </Appare>
            </li>
            <li>
              <Appare ritardo={0.2}>
                <h3>La quota dei soci non cala</h3>
                <p>Se il raccolto delude, le bottiglie in meno escono dalla vendita online, poi dai bar.</p>
              </Appare>
            </li>
          </ul>
        </div>
      </section>
    </>
  );
}
