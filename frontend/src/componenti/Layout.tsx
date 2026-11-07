import { useEffect } from 'react';
import { Outlet, useNavigate } from 'react-router';

import logoChiaroPng from '../assets/logo-chiaro.png';
import logoChiaroWebp from '../assets/logo-chiaro.webp';
import { prendiPaginaDopoAccesso } from '../sessione/accesso';
import { useSessione } from '../sessione/Sessione';
import { Avviso } from './Avviso';
import { Intestazione } from './Intestazione';
import { Divisore } from './Montagne';

export function Layout() {
  const { stato, ricarica } = useSessione();
  const naviga = useNavigate();

  // Appena l'accesso è confermato, riapre la pagina che l'utente voleva vedere
  useEffect(() => {
    if (stato.tipo === 'autenticato') {
      const pagina = prendiPaginaDopoAccesso();
      if (pagina) {
        naviga(pagina, { replace: true });
      }
    }
  }, [stato.tipo, naviga]);

  return (
    <>
      <a className="salta" href="#contenuto">
        Vai al contenuto
      </a>
      <Intestazione />
      {stato.tipo === 'irraggiungibile' && (
        <div className="contenitore spazio-sopra">
          <Avviso tipo="problema" titolo="GinVault non risponde">
            <p>
              Non riusciamo a sapere se hai fatto l’accesso: la pagina pubblica resta consultabile, il resto no.{' '}
              {import.meta.env.DEV && (
                <>
                  In sviluppo controlla che il gateway risponda su http://localhost:8080 (vedi frontend/README.md).{' '}
                </>
              )}
              <button type="button" className="pulsante-testo" onClick={() => void ricarica()}>
                Riprova
              </button>
            </p>
          </Avviso>
        </div>
      )}
      <main id="contenuto" className="contenuto">
        <Outlet />
      </main>
      <footer className="piede">
        <Divisore da="sopra" a="abete" variante={3} className="piede__montagne" />
        <div className="piede__corpo">
          <div className="contenitore piede__griglia">
            <picture>
              <source srcSet={logoChiaroWebp} type="image/webp" />
              <img className="piede__logo" src={logoChiaroPng} alt="GinVault" width={400} height={279} loading="lazy" />
            </picture>
            <div className="piede__testi">
              <p>Gin di montagna in edizioni stagionali.</p>
              <p>Progetto TAASS 2026/27.</p>
              <p>Fotografie da Unsplash: autori e licenza nel README del frontend.</p>
            </div>
          </div>
        </div>
      </footer>
    </>
  );
}
