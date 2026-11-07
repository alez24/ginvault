import { domAnimation, LazyMotion, MotionConfig } from 'motion/react';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';

// Font dentro la build (pacchetti @fontsource), non da una CDN: nessuna
// richiesta a server esterni e funzionano anche offline.
import '@fontsource/eb-garamond/400.css';
import '@fontsource/eb-garamond/500.css';
import '@fontsource/eb-garamond/400-italic.css';
import '@fontsource/inter-tight/400.css';
import '@fontsource/inter-tight/500.css';
import '@fontsource/inter-tight/600.css';
import './stile.css';
import './stile-distilleria.css';
import './stile-soci.css';

import { App } from './App';
import { SessioneProvider } from './sessione/Sessione';

const radice = document.getElementById('root');
if (!radice) {
  throw new Error('Manca <div id="root"> in index.html');
}

createRoot(radice).render(
  <StrictMode>
    <BrowserRouter>
      <SessioneProvider>
        {/* Solo le funzioni di motion che servono (animazioni, vista, hover), e il
            movimento si spegne da solo se il sistema chiede di ridurlo */}
        <LazyMotion features={domAnimation} strict>
          <MotionConfig reducedMotion="user">
            <App />
          </MotionConfig>
        </LazyMotion>
      </SessioneProvider>
    </BrowserRouter>
  </StrictMode>,
);
