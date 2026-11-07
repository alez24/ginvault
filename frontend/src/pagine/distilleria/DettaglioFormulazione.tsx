import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';

import { ErroreApi } from '../../api/http';
import { ricette, type Formulazione, type Prova } from '../../api/ricette';
import { Avviso } from '../../componenti/Avviso';
import { AvvisoDiArrivo, DosiRicetta, EtichettaEsito, EtichettaStato } from '../../componenti/Distilleria';
import { EditorDosi, nuovaRiga, righeDa } from '../../componenti/EditorDosi';
import { ErroreOperazione } from '../../componenti/ErroreOperazione';
import { TestataPagina } from '../../componenti/TestataPagina';
import { PAGINE } from '../../navigazione';
import { ilGiorno, maiuscola } from '../../utilita/numeri';
import { controllaDosi, type RigaDose } from '../../utilita/regole';
import { useCaricamento } from '../../utilita/useCaricamento';
import { ModuloEdizione } from './ModuloEdizione';

/** GET /api/ricette/formulazioni/{id}: le prove, la scelta del panel e, da SCELTA, l'edizione. */
export function DettaglioFormulazione() {
  const { id = '' } = useParams();
  const { risultato, ricarica, imposta } = useCaricamento(() => ricette.trova(id), id);
  const [notizia, setNotizia] = useState<string | null>(null);

  if (risultato.stato === 'caricamento') {
    return <p className="contenitore attesa">Carico la formulazione…</p>;
  }
  if (risultato.stato === 'errore') {
    const nonTrovata = risultato.errore.stato === 404 || risultato.errore.stato === 400;
    return (
      <section className="pagina">
        <title>Formulazione · GinVault</title>
        <TestataPagina
          ritorno={{ a: PAGINE.formulazioni.percorso, testo: 'Tutte le formulazioni' }}
          titolo={nonTrovata ? 'Formulazione non trovata' : 'Formulazione'}
          attacco={nonTrovata ? 'L’indirizzo non corrisponde a nessuna formulazione registrata.' : undefined}
        />
        {!nonTrovata && (
          <div className="contenitore pagina__corpo">
            <ErroreOperazione errore={risultato.errore} onRiprova={ricarica} />
          </div>
        )}
      </section>
    );
  }

  const formulazione = risultato.dati;
  const aggiornata = (nuova: Formulazione, testo: string) => {
    imposta(nuova);
    setNotizia(testo);
  };

  return (
    <section className="pagina">
      <title>{`${formulazione.nome} · GinVault`}</title>
      <TestataPagina
        ritorno={{ a: PAGINE.formulazioni.percorso, testo: 'Tutte le formulazioni' }}
        sopra={<EtichettaStato stato={formulazione.stato} />}
        titolo={formulazione.nome}
        attacco={`${maiuscola(formulazione.botanicaDiStagione)} di stagione, ${
          formulazione.prove.length === 1 ? 'una prova d’assaggio' : `${formulazione.prove.length} prove d’assaggio`
        }.`}
      />
      <div className="contenitore pagina__corpo">

      {/* L'ultima notizia prende il posto di quella portata dalla pagina precedente */}
      {notizia ? (
        <div className="spazio-sotto">
          <Avviso titolo={notizia} onChiudi={() => setNotizia(null)} />
        </div>
      ) : (
        <AvvisoDiArrivo />
      )}

      <div className="due-colonne">
        <div>
          <h2>Prove d’assaggio</h2>
          <ol className="prove">
            {formulazione.prove.map((prova) => (
              <ProvaDiAssaggio
                key={prova.versione}
                formulazione={formulazione}
                prova={prova}
                onScelta={(nuova) =>
                  aggiornata(nuova, `Il panel ha scelto la versione ${prova.versione}: ora puoi creare l’edizione.`)
                }
                onRicarica={ricarica}
              />
            ))}
          </ol>
          {formulazione.stato === 'IN_PROVA' && (
            <NuovaProva
              formulazione={formulazione}
              onAggiunta={(nuova) =>
                aggiornata(nuova, `Versione ${nuova.prove.length} registrata: ora è in assaggio.`)
              }
              onRicarica={ricarica}
            />
          )}
        </div>

        <aside className="a-lato-fisso">
          {formulazione.stato === 'IN_PROVA' && (
            <div className="scheda">
              <h2>In assaggio</h2>
              <p>
                Il panel assaggia le versioni. Quando ha deciso, scegli la versione con «Scegli questa versione»: le
                altre vengono archiviate e la formulazione è pronta per l’edizione.
              </p>
            </div>
          )}
          {formulazione.stato === 'SCELTA' && <ModuloEdizione formulazione={formulazione} />}
          {formulazione.stato === 'USATA' && (
            <div className="scheda">
              <h2>Usata per un’edizione</h2>
              <p>
                La versione {formulazione.versioneScelta} è diventata un’edizione. Una formulazione si usa una volta
                sola: per la prossima stagione ne serve una nuova.
              </p>
              {formulazione.edizioneId && (
                <Link className="pulsante pulsante--contorno" to={`${PAGINE.edizioni.percorso}/${formulazione.edizioneId}`}>
                  Vai all’edizione
                </Link>
              )}
            </div>
          )}
        </aside>
      </div>
      </div>
    </section>
  );
}

interface PropsProva {
  formulazione: Formulazione;
  prova: Prova;
  onScelta: (nuova: Formulazione) => void;
  onRicarica: () => void;
}

/** Una versione con le sue dosi e, finché il panel non ha scelto, il pulsante per sceglierla. */
function ProvaDiAssaggio({ formulazione, prova, onScelta, onRicarica }: PropsProva) {
  const [conferma, setConferma] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);
  const scelta = prova.esito === 'SCELTA';

  const scegli = async () => {
    setInvio(true);
    setErrore(null);
    try {
      onScelta(await ricette.scegli(formulazione.id, prova.versione));
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
    } finally {
      setInvio(false);
      setConferma(false);
    }
  };

  return (
    <li className={`prova${scelta ? ' prova--scelta' : ''}${prova.esito === 'ARCHIVIATA' ? ' prova--archiviata' : ''}`}>
      <div className="prova__testa">
        <h3>Versione {prova.versione}</h3>
        <EtichettaEsito esito={prova.esito} />
      </div>
      <p className="nota">Registrata {ilGiorno(prova.registrataIl)}</p>
      <DosiRicetta dosi={prova.dosi} botanicaDiStagione={formulazione.botanicaDiStagione} />

      {formulazione.stato === 'IN_PROVA' &&
        (conferma ? (
          <div className="conferma" role="group" aria-label={`Conferma la scelta della versione ${prova.versione}`}>
            <p>
              Il panel sceglie la versione {prova.versione}? Le altre verranno archiviate e non si potranno più
              aggiungere prove.
            </p>
            <div className="modulo__azioni">
              <button type="button" className="pulsante" onClick={() => void scegli()} disabled={invio}>
                {invio ? 'Registro la scelta…' : 'Sì, scegli questa'}
              </button>
              <button type="button" className="pulsante-testo" onClick={() => setConferma(false)} disabled={invio}>
                Annulla
              </button>
            </div>
          </div>
        ) : (
          <button type="button" className="pulsante pulsante--contorno" onClick={() => setConferma(true)}>
            Scegli questa versione
          </button>
        ))}

      {errore && (
        <ErroreOperazione
          errore={errore}
          onRiprova={() => void scegli()}
          onRicarica={onRicarica}
          onChiudi={() => setErrore(null)}
        />
      )}
    </li>
  );
}

interface PropsNuovaProva {
  formulazione: Formulazione;
  onAggiunta: (nuova: Formulazione) => void;
  onRicarica: () => void;
}

/** POST /{id}/versioni: si parte dalle dosi dell'ultima versione e si ritocca. */
function NuovaProva({ formulazione, onAggiunta, onRicarica }: PropsNuovaProva) {
  const [aperta, setAperta] = useState(false);
  const [righe, setRighe] = useState<RigaDose[]>([]);
  const [mostraErrori, setMostraErrori] = useState(false);
  const [invio, setInvio] = useState(false);
  const [errore, setErrore] = useState<ErroreApi | null>(null);
  const controllo = controllaDosi(righe, formulazione.botanicaDiStagione);

  const apri = () => {
    const ultima = formulazione.prove.at(-1);
    setRighe(ultima ? [...righeDa(ultima.dosi), nuovaRiga()] : [nuovaRiga()]);
    setMostraErrori(false);
    setErrore(null);
    setAperta(true);
  };

  const salva = async (evento?: FormEvent) => {
    evento?.preventDefault();
    setMostraErrori(true);
    if (!controllo.dosi) {
      return;
    }
    setInvio(true);
    setErrore(null);
    try {
      onAggiunta(await ricette.aggiungiProva(formulazione.id, controllo.dosi));
      setAperta(false);
    } catch (e) {
      setErrore(e instanceof ErroreApi ? e : new ErroreApi(0, 'Errore imprevisto'));
    } finally {
      setInvio(false);
    }
  };

  if (!aperta) {
    return (
      <button type="button" className="pulsante" onClick={apri}>
        Aggiungi una prova
      </button>
    );
  }

  return (
    <form className="modulo scheda" onSubmit={(evento) => void salva(evento)} noValidate>
      <h3>Versione {formulazione.prove.length + 1}</h3>
      <p className="nota">Parti dalle dosi della versione {formulazione.prove.length} e cambia quello che serve.</p>
      <EditorDosi
        righe={righe}
        onCambia={setRighe}
        botanicaDiStagione={formulazione.botanicaDiStagione}
        controllo={mostraErrori ? controllo : null}
      />
      {errore && (
        <ErroreOperazione
          errore={errore}
          onRiprova={() => void salva()}
          onRicarica={onRicarica}
          onChiudi={() => setErrore(null)}
        />
      )}
      <div className="modulo__azioni">
        <button type="submit" className="pulsante" disabled={invio}>
          {invio ? 'Registro…' : 'Registra la prova'}
        </button>
        <button type="button" className="pulsante-testo" onClick={() => setAperta(false)} disabled={invio}>
          Annulla
        </button>
      </div>
    </form>
  );
}
