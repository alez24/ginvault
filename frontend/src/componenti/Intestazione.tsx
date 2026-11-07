import { useEffect, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router';

import { ErroreApi } from '../api/http';
import logoPng from '../assets/logo.png';
import logoWebp from '../assets/logo.webp';
import { vociDelMenu } from '../navigazione';
import { useSessione } from '../sessione/Sessione';
import { Avviso } from './Avviso';

/** Il logo, il menu (diverso per ruolo), il nome dell'utente ed "Esci". */
export function Intestazione() {
  const { stato, utente, esci } = useSessione();
  const [menuAperto, setMenuAperto] = useState(false);
  const [erroreUscita, setErroreUscita] = useState<ErroreApi | null>(null);
  const { pathname } = useLocation();
  const naviga = useNavigate();

  // Sul telefono il menu si richiude quando si cambia pagina
  useEffect(() => {
    setMenuAperto(false);
  }, [pathname]);

  async function gestisciUscita() {
    setErroreUscita(null);
    try {
      await esci();
      naviga('/');
    } catch (errore) {
      setErroreUscita(errore instanceof ErroreApi ? errore : new ErroreApi(0, 'Uscita non riuscita'));
    }
  }

  return (
    <header className="intestazione">
      <div className="contenitore intestazione__barra">
        <Link to="/" className="marchio">
          {/* 184 px: il doppio di come appare sul computer, per gli schermi retina */}
          <picture>
            <source srcSet={logoWebp} type="image/webp" />
            <img src={logoPng} alt="GinVault" width={184} height={129} />
          </picture>
        </Link>

        <button
          type="button"
          className="intestazione__interruttore"
          aria-expanded={menuAperto}
          aria-controls="menu-principale"
          onClick={() => setMenuAperto((aperto) => !aperto)}
        >
          {menuAperto ? 'Chiudi' : 'Menu'}
        </button>

        <nav id="menu-principale" className={menuAperto ? 'menu menu--aperto' : 'menu'} aria-label="Menu principale">
          <ul className="menu__voci">
            {vociDelMenu(utente).map((pagina) => (
              <li key={pagina.percorso}>
                <NavLink to={pagina.percorso} end={pagina.percorso === '/'}>
                  {pagina.etichetta}
                </NavLink>
              </li>
            ))}
          </ul>

          <div className="menu__utente">
            {utente && (
              <>
                <span className="menu__nome">{utente.nome ?? 'Il tuo account'}</span>
                <button type="button" className="pulsante-testo" onClick={gestisciUscita}>
                  Esci
                </button>
              </>
            )}
            {stato.tipo === 'anonimo' && <NavLink to="/accedi">Accedi</NavLink>}
          </div>
        </nav>
      </div>

      {erroreUscita && (
        <div className="contenitore">
          <Avviso tipo="problema" titolo={erroreUscita.titolo} onChiudi={() => setErroreUscita(null)}>
            {erroreUscita.dettaglio}
          </Avviso>
        </div>
      )}
    </header>
  );
}
