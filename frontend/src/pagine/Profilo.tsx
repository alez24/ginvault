import { TestataPagina } from '../componenti/TestataPagina';
import { RUOLI } from '../navigazione';
import { providerDi } from '../sessione/accesso';
import { useSessione } from '../sessione/Sessione';
import type { Ruolo } from '../sessione/tipi';

/** "Il mio profilo": i dati di GET /auth/me. */
export function Profilo() {
  const { utente } = useSessione();
  if (!utente) {
    return null; // la route è protetta: qui l'utente c'è sempre
  }

  return (
    <section className="pagina">
      <title>Il mio profilo · GinVault</title>
      <TestataPagina titolo={utente.nome ?? 'Il tuo account'} attacco="Il tuo profilo e i ruoli che hai in distilleria." />

      <div className="contenitore pagina__corpo due-colonne">
        <dl className="scheda dati">
          <dt>Nome</dt>
          <dd>{utente.nome ?? '—'}</dd>
          <dt>Email</dt>
          <dd>{utente.email ?? 'non condivisa dal provider'}</dd>
          <dt>Accesso con</dt>
          <dd>{providerDi(utente.sub)}</dd>
          <dt>Identificativo</dt>
          <dd>
            <code>{utente.sub}</code>
          </dd>
        </dl>

        <div>
          <h2>I tuoi ruoli</h2>
          <ul className="ruoli">
            {utente.ruoli.map((ruolo) => {
              const noto = ruolo in RUOLI ? RUOLI[ruolo as Ruolo] : undefined;
              return (
                <li key={ruolo} className="scheda ruolo">
                  <h3 className="ruolo__nome">{noto?.nome ?? ruolo}</h3>
                  {noto && <p>{noto.descrizione}</p>}
                </li>
              );
            })}
          </ul>
          <p className="nota">
            Questi dati li fornisce il gateway (<code>GET /auth/me</code>) leggendo la tua sessione. Il browser non
            riceve mai il JWT con cui il gateway parla ai servizi.
          </p>
        </div>
      </div>
    </section>
  );
}
