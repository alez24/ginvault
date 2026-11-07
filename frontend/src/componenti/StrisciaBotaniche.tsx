/*
 * La striscia delle botaniche che scorre in orizzontale. Due copie dello stesso
 * elenco una dopo l'altra: quando la prima è uscita del tutto, la seconda è
 * esattamente al suo posto e il giro ricomincia senza salti.
 * Si anima solo transform (con le Web Animations, fuori dal thread principale);
 * si ferma fuori dallo schermo, sotto il mouse e con il pulsante. Con «riduci
 * movimento» resta ferma e le botaniche vanno a capo.
 */
import { useAnimate, useInView, useReducedMotion } from 'motion/react';
import { useEffect, useRef, useState, type ComponentType } from 'react';

import { Genziana, Ginepro, Lavanda, PinoMugo } from './illustrazioni/Illustrazioni';

const BOTANICHE: { nome: string; latino: string; parte: string; Disegno: ComponentType<{ className?: string }> }[] = [
  { nome: 'Ginepro', latino: 'Juniperus communis', parte: 'Le bacche, in ogni edizione.', Disegno: Ginepro },
  { nome: 'Lavanda', latino: 'Lavandula angustifolia', parte: 'I fiori, per l’edizione d’estate.', Disegno: Lavanda },
  { nome: 'Genziana', latino: 'Gentiana lutea', parte: 'La radice, amara e profumata.', Disegno: Genziana },
  { nome: 'Pino mugo', latino: 'Pinus mugo', parte: 'Le gemme, resinose e balsamiche.', Disegno: PinoMugo },
];

function Elenco({ copia = false }: { copia?: boolean }) {
  return (
    <ul className="striscia__elenco" aria-hidden={copia || undefined}>
      {BOTANICHE.map(({ nome, latino, parte, Disegno }) => (
        <li key={nome} className="botanica">
          <span className="botanica__disegno" aria-hidden="true">
            <Disegno />
          </span>
          <span className="botanica__testo">
            <span className="botanica__nome">{nome}</span>
            <span className="botanica__latino">{latino}</span>
            <span className="botanica__parte">{parte}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}

export function StrisciaBotaniche() {
  const fermo = useReducedMotion();
  const [nastro, anima] = useAnimate<HTMLDivElement>();
  const finestra = useRef<HTMLDivElement>(null);
  const inVista = useInView(finestra);
  const [inPausa, setInPausa] = useState(false);
  const [sotto, setSotto] = useState(false);
  const scorrimento = useRef<ReturnType<typeof anima> | null>(null);

  useEffect(() => {
    if (fermo || !nastro.current) {
      return;
    }
    const giro = anima(
      nastro.current,
      { transform: ['translateX(0%)', 'translateX(-50%)'] },
      { duration: 52, ease: 'linear', repeat: Infinity },
    );
    scorrimento.current = giro;
    return () => {
      giro.stop();
      scorrimento.current = null;
    };
  }, [fermo, anima, nastro]);

  useEffect(() => {
    const giro = scorrimento.current;
    if (!giro) {
      return;
    }
    if (inVista && !inPausa && !sotto) {
      giro.play();
    } else {
      giro.pause();
    }
  }, [inVista, inPausa, sotto, fermo]);

  return (
    <section className="striscia" aria-labelledby="striscia-titolo">
      <div className="contenitore striscia__testa">
        <h2 id="striscia-titolo">Le botaniche delle nostre montagne</h2>
        {!fermo && (
          <button type="button" className="pulsante-testo striscia__pausa" onClick={() => setInPausa((p) => !p)}>
            {inPausa ? 'Fai scorrere le botaniche' : 'Ferma le botaniche'}
          </button>
        )}
      </div>
      <div
        ref={finestra}
        className="striscia__finestra"
        onPointerEnter={() => setSotto(true)}
        onPointerLeave={() => setSotto(false)}
      >
        <div ref={nastro} className={fermo ? 'striscia__nastro striscia__nastro--fermo' : 'striscia__nastro'}>
          <Elenco />
          {!fermo && <Elenco copia />}
        </div>
      </div>
    </section>
  );
}
