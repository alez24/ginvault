import { useState } from 'react';
import { Link } from 'react-router';

import { NOMI_STATI_PRENOTAZIONE, numeriBottiglie, percorsoEdizioneSoci, soci, type Prenotazione } from '../../api/soci';
import { PAESAGGIO } from '../../assets/foto/foto';
import { Avviso } from '../../componenti/Avviso';
import { Bottiglia } from '../../componenti/Bottiglia';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { Divisore } from '../../componenti/Montagne';
import { Appare } from '../../componenti/Movimento';
import { useSessione } from '../../sessione/Sessione';
import { bottiglie, data, giornoConNome, ilGiorno, numero, ora } from '../../utilita/numeri';
import { useCaricamento } from '../../utilita/useCaricamento';

/**
 * Area soci (storia di Giulia): gli avvisi del club, le prenotazioni e le regole
 * della prelazione. I dati per ora sono finti (src/mock/soci.ts).
 */
export function AreaSoci() {
  const { utente } = useSessione();
  const { risultato, ricarica } = useCaricamento(() => soci.area(), 'area-soci');
  const [chiuse, setChiuse] = useState<string[]>([]);
  const nome = utente?.nome?.split(' ')[0];

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
            {nome ? `Ciao ${nome}: qui` : 'Qui'} trovi le tue prenotazioni, gli avvisi del club e la storia delle tue
            bottiglie, dalla ricetta al controllo qualità.
          </p>
        </div>
        <Divisore da="trasparente" a="neve" variante={2} />
      </header>

      {risultato.stato === 'caricamento' && <p className="contenitore attesa">Carico le tue prenotazioni…</p>}
      {risultato.stato === 'errore' && (
        <div className="contenitore soci__corpo">
          <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
        </div>
      )}
      {risultato.stato === 'pronto' && (
        <>
          <div className="contenitore soci__corpo">
            {risultato.dati.notifiche.some((n) => !chiuse.includes(n.id)) && (
              <div className="soci__avvisi" aria-label="Avvisi del club">
                {risultato.dati.notifiche
                  .filter((n) => !chiuse.includes(n.id))
                  .map((notifica) => (
                    <Avviso
                      key={notifica.id}
                      titolo={notifica.titolo}
                      onChiudi={() => setChiuse((elenco) => [...elenco, notifica.id])}
                    >
                      <p>{notifica.testo}</p>
                      <p className="avviso__piede">
                        Arrivato {ilGiorno(notifica.arrivataIl)}.{' '}
                        <Link to={percorsoEdizioneSoci(notifica.idEdizione)}>Apri l’edizione</Link>
                      </p>
                    </Avviso>
                  ))}
              </div>
            )}

            <section className="soci__sezione" aria-labelledby="prenotazioni-titolo">
              <h2 id="prenotazioni-titolo">Le tue prenotazioni</h2>
              {risultato.dati.prenotazioni.length === 0 ? (
                <p className="vuoto">
                  Non hai ancora prenotazioni. Quando un’edizione viene annunciata hai{' '}
                  {risultato.dati.club.giorniDiPrelazione} giorni per prenotare, prima di tutti.
                </p>
              ) : (
                <ul className="prenotazioni">
                  {risultato.dati.prenotazioni.map((prenotazione) => (
                    <SchedaPrenotazione key={prenotazione.id} prenotazione={prenotazione} />
                  ))}
                </ul>
              )}
            </section>
          </div>

          <Divisore da="neve" a="avorio" variante={3} />
          <section className="regole fondo-avorio" aria-labelledby="regole-titolo">
            <div className="contenitore">
              <h2 id="regole-titolo">Le regole del club</h2>
              <ul className="regole__elenco">
                <li>
                  <Appare>
                    <h3>{risultato.dati.club.giorniDiPrelazione} giorni di prelazione</h3>
                    <p>
                      Quando un’edizione viene annunciata puoi prenotare prima dei bar e della vendita online.
                    </p>
                  </Appare>
                </li>
                <li>
                  <Appare ritardo={0.1}>
                    <h3>Al massimo {bottiglie(risultato.dati.club.bottigliePerSocio)}</h3>
                    <p>Per ogni edizione, ognuna con il suo numero, scritto sul cartellino.</p>
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
      )}
    </section>
  );
}

/** Una prenotazione: l'edizione, i numeri delle bottiglie e il ritiro. Tutta la scheda porta all'edizione. */
function SchedaPrenotazione({ prenotazione: p }: { prenotazione: Prenotazione }) {
  return (
    <li className={/estate/i.test(p.edizione.nome) ? 'carta prenotazione prenotazione--estate' : 'carta prenotazione'}>
      <div className="prenotazione__figura" aria-hidden="true">
        <Bottiglia decorativa sizes="3rem" />
      </div>
      <div className="prenotazione__testo">
        <p className={`stato stato--${p.stato.toLowerCase()}`}>{NOMI_STATI_PRENOTAZIONE[p.stato]}</p>
        <h3 className="carta__titolo">
          <Link className="carta__collegamento" to={percorsoEdizioneSoci(p.edizione.id)}>
            {p.edizione.nome}
          </Link>
        </h3>
        <p className="prenotazione__numeri">
          {p.numeri.length > 0
            ? `Bottiglie ${numeriBottiglie(p.numeri)} di ${numero(p.edizione.tiratura)}`
            : 'Il numero arriva con l’imbottigliamento'}
        </p>
        <dl className="prenotazione__dati">
          <div>
            <dt>Ritiro</dt>
            <dd>
              Alla {p.ritiro.occasione}, {giornoConNome(p.ritiro.inizio)} alle {ora(p.ritiro.inizio)}
            </dd>
          </div>
          <div>
            <dt>Prenotata</dt>
            <dd>{data(p.prenotataIl)}</dd>
          </div>
        </dl>
      </div>
    </li>
  );
}
