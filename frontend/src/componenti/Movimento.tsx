/*
 * Il movimento della home, con la libreria motion: testi e immagini che entrano
 * con una dissolvenza la prima volta che arrivano sullo schermo, numeri che contano.
 * Chi ha chiesto meno movimento al sistema vede subito tutto fermo e al suo posto.
 * Si animano solo opacità e transform, che il browser compone senza ricalcolare
 * la pagina.
 */
import { animate, m, useInView, useReducedMotion } from 'motion/react';
import { useEffect, useRef, type ReactNode } from 'react';

import { numero } from '../utilita/numeri';

const DOLCE = [0.22, 1, 0.36, 1] as const;

interface PropsAppare {
  children: ReactNode;
  className?: string;
  /** Ritardo in secondi, per far entrare in fila gli elementi vicini. */
  ritardo?: number;
}

/** Entra con una dissolvenza (e un piccolo movimento verso l'alto) al primo scroll. */
export function Appare({ children, className, ritardo = 0 }: PropsAppare) {
  const fermo = useReducedMotion();
  if (fermo) {
    return <div className={className}>{children}</div>;
  }
  return (
    <m.div
      className={className}
      initial={{ opacity: 0, y: 22 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.2 }}
      transition={{ duration: 0.8, ease: DOLCE, delay: ritardo }}
    >
      {children}
    </m.div>
  );
}

/**
 * Un numero che conta da zero quando entra in vista. Gli screen reader leggono
 * subito il valore finale; il conteggio scrive direttamente nel DOM, senza
 * ridisegnare React a ogni fotogramma.
 */
export function Contatore({ valore, durata = 1.6 }: { valore: number; durata?: number }) {
  const cifra = useRef<HTMLSpanElement>(null);
  const inVista = useInView(cifra, { once: true, amount: 0.8 });
  const fermo = useReducedMotion();

  useEffect(() => {
    const elemento = cifra.current;
    if (!inVista || fermo || !elemento) {
      return;
    }
    const conteggio = animate(0, valore, {
      duration: durata,
      ease: DOLCE,
      onUpdate: (v) => {
        elemento.textContent = numero(Math.round(v));
      },
    });
    return () => conteggio.stop();
  }, [inVista, fermo, valore, durata]);

  // Il valore finale, invisibile, tiene la larghezza: l'unità accanto non si sposta
  const finale = numero(valore);
  return (
    <span className="contatore">
      <span className="contatore__misura" aria-hidden="true">
        {finale}
      </span>
      <span ref={cifra} className="contatore__cifra" aria-hidden="true">
        {fermo ? finale : '0'}
      </span>
      <span className="nascosto">{finale}</span>
    </span>
  );
}
