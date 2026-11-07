import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router';

import { accessibile, nomeDelRuolo, ruoliRichiesti, type Pagina } from '../navigazione';
import { useSessione } from '../sessione/Sessione';
import { TestataPagina } from './TestataPagina';

interface Props {
  pagina: Pagina;
  children: ReactNode;
}

/**
 * Route protetta: senza accesso porta a /accedi (ricordando la pagina), con
 * l'accesso ma senza il ruolo giusto spiega perché non si può entrare.
 */
export function Protetta({ pagina, children }: Props) {
  const { stato } = useSessione();
  const posizione = useLocation();

  switch (stato.tipo) {
    case 'caricamento':
      return <p className="contenitore attesa">Un momento…</p>;
    case 'irraggiungibile':
      // L'avviso è già nel Layout
      return null;
    case 'anonimo':
      return <Navigate to="/accedi" replace state={{ da: posizione.pathname + posizione.search }} />;
    case 'autenticato':
      if (accessibile(stato.utente, pagina)) {
        return children;
      }
      return (
        <section className="pagina">
          <title>{`Pagina riservata · GinVault`}</title>
          <TestataPagina titolo={pagina.etichetta} attacco="Questa pagina è riservata." />
          <div className="contenitore pagina__corpo testo-corrente">
          <p>
            Questa pagina è per chi ha il ruolo <strong>{ruoliRichiesti(pagina)}</strong>. Il tuo account ha:{' '}
            {stato.utente.ruoli.map(nomeDelRuolo).join(', ') || 'nessun ruolo'}.
          </p>
          <p>
            Se pensi che sia un errore, chiedi a un amministratore di aggiungerti. Intanto puoi tornare alla{' '}
            <Link to="/">pagina della distilleria</Link>.
          </p>
          </div>
        </section>
      );
  }
}
