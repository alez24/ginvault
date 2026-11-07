/*
 * Geometria dei disegni botanici al tratto (ginepro, lavanda, genziana, pino mugo)
 * e delle sagome delle montagne.
 * Sono generati da curve di Bézier con un caso "seminato": stesso seme, stesso
 * disegno a ogni build, ma con l'irregolarità di un tratto fatto a mano.
 * Qui solo numeri e stringhe di path: i componenti React li disegnano.
 */

interface P {
  x: number;
  y: number;
}

/** Generatore pseudo-casuale con seme (mulberry32): deterministico. */
function casuale(seme: number): () => number {
  let s = seme;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const arrotonda = (n: number) => Math.round(n * 10) / 10;
const punto = (p: P) => `${arrotonda(p.x)} ${arrotonda(p.y)}`;
const somma = (a: P, b: P, k = 1): P => ({ x: a.x + b.x * k, y: a.y + b.y * k });
const normalizza = (v: P): P => {
  const l = Math.hypot(v.x, v.y) || 1;
  return { x: v.x / l, y: v.y / l };
};
/** Rotazione in senso orario sullo schermo (y verso il basso). */
const ruota = (v: P, a: number): P => ({
  x: v.x * Math.cos(a) - v.y * Math.sin(a),
  y: v.x * Math.sin(a) + v.y * Math.cos(a),
});
const normale = (v: P): P => ({ x: -v.y, y: v.x });

type Curva = (t: number) => P;

const cubica =
  (p0: P, p1: P, p2: P, p3: P): Curva =>
  (t) => {
    const u = 1 - t;
    return {
      x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
      y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
    };
  };

const quadratica =
  (p0: P, p1: P, p2: P): Curva =>
  (t) => {
    const u = 1 - t;
    return { x: u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x, y: u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y };
  };

/** Direzione della curva in t (verso la punta). */
function direzione(curva: Curva, t: number): P {
  const a = curva(Math.max(0, t - 0.005));
  const b = curva(Math.min(1, t + 0.005));
  return normalizza({ x: b.x - a.x, y: b.y - a.y });
}

/** Un cerchio come path (per disegnarlo insieme agli altri tratti). */
function cerchio(c: P, r: number): string {
  const rr = arrotonda(r);
  return `M ${punto({ x: c.x - r, y: c.y })} a ${rr} ${rr} 0 1 0 ${arrotonda(2 * r)} 0 a ${rr} ${rr} 0 1 0 ${arrotonda(-2 * r)} 0`;
}

/** Petalo, foglia o fiore: una mandorla dalla base `a` verso `d`, lunga `l` e larga `w`. */
function mandorla(a: P, d: P, l: number, w: number, punta = 0.15): string {
  const n = normale(d);
  const cima = somma(a, d, l);
  const c1 = somma(somma(a, d, l * 0.3), n, w * 0.75);
  const c2 = somma(somma(cima, d, -l * punta), n, w);
  const c3 = somma(somma(cima, d, -l * punta), n, -w);
  const c4 = somma(somma(a, d, l * 0.3), n, -w * 0.75);
  return `M ${punto(a)} C ${punto(c1)} ${punto(c2)} ${punto(cima)} C ${punto(c3)} ${punto(c4)} ${punto(a)} Z`;
}

// ------------------------------------------------------------------ ginepro

export interface Ramo {
  /** Il legno del ramo. */
  linea: string;
  /** Tutti gli aghi del ramo in un solo path (si disegnano uno dopo l'altro). */
  aghi: string;
}

export interface Bacca {
  cx: number;
  cy: number;
  r: number;
  /** Il piccolo segno a tre punte in cima alla bacca. */
  segno: string;
  /** Un arco di luce sul lato sinistro, per dare volume. */
  riflesso: string;
}

export interface DisegnoGinepro {
  larghezza: number;
  altezza: number;
  rami: Ramo[];
  bacche: Bacca[];
}

/** Aghi a coppie lungo una curva, inclinati verso la punta e più corti in cima. */
function aghi(curva: Curva, da: number, a: number, quanti: number, lunghezza: number, caso: () => number): string {
  const tratti: string[] = [];
  for (let k = 0; k <= quanti; k++) {
    const u = da + ((a - da) * k) / quanti;
    const p = curva(u);
    const d = direzione(curva, u);
    const verso = (u - da) / (a - da);
    const riduzione = 1 - 0.55 * verso * verso;
    for (const lato of [-1, 1]) {
      const angolo = lato * (0.72 + caso() * 0.32);
      const l = lunghezza * (0.75 + caso() * 0.45) * riduzione;
      tratti.push(`M ${punto(p)} L ${punto(somma(p, ruota(d, angolo), l))}`);
    }
    if (k % 2 === 0) {
      const l = lunghezza * 0.55 * riduzione;
      tratti.push(`M ${punto(p)} L ${punto(somma(p, ruota(d, (caso() - 0.5) * 0.5), l))}`);
    }
  }
  return tratti.join(' ');
}

function bacca(c: P, r: number): Bacca {
  // Tre raggi corti attorno a un punto poco sopra il centro, come la cicatrice a stella delle bacche
  const centro = { x: c.x - r * 0.15, y: c.y - r * 0.3 };
  const segno = [0, (2 * Math.PI) / 3, (4 * Math.PI) / 3]
    .map((a) => `M ${punto(centro)} L ${punto(somma(centro, ruota({ x: 0, y: -1 }, a), r * 0.32))}`)
    .join(' ');
  const da = { x: c.x + r * 0.62 * Math.cos(3.6), y: c.y + r * 0.62 * Math.sin(3.6) };
  const a = { x: c.x + r * 0.62 * Math.cos(4.5), y: c.y + r * 0.62 * Math.sin(4.5) };
  const riflesso = `M ${punto(da)} A ${arrotonda(r * 0.62)} ${arrotonda(r * 0.62)} 0 0 1 ${punto(a)}`;
  return { cx: arrotonda(c.x), cy: arrotonda(c.y), r: arrotonda(r), segno, riflesso };
}

/** Un rametto di ginepro comune (Juniperus communis) con bacche. viewBox 0 0 420 760. */
export function ginepro(seme = 11): DisegnoGinepro {
  const caso = casuale(seme);
  const p0 = { x: 262, y: 752 };
  const p1 = { x: 205, y: 560 };
  const p2 = { x: 282, y: 330 };
  const p3 = { x: 210, y: 36 };
  const fusto = cubica(p0, p1, p2, p3);
  const rami: Ramo[] = [
    { linea: `M ${punto(p0)} C ${punto(p1)} ${punto(p2)} ${punto(p3)}`, aghi: aghi(fusto, 0.2, 1, 44, 27, caso) },
  ];
  const bacche: Bacca[] = [];

  const attacchi = [0.25, 0.37, 0.47, 0.58, 0.67, 0.77, 0.86];
  attacchi.forEach((base, i) => {
    const t = base + (caso() - 0.5) * 0.04;
    const lato = i % 2 === 0 ? -1 : 1;
    const inizio = fusto(t);
    const d = ruota(direzione(fusto, t), lato * (0.62 + caso() * 0.38));
    const lunghezza = (185 - t * 115) * (0.7 + caso() * 0.5);
    // Il ramo si piega verso l'alto, come cercando la luce
    const fine = { x: inizio.x + d.x * lunghezza, y: inizio.y + d.y * lunghezza - lunghezza * 0.22 };
    const controllo = somma(somma(inizio, d, lunghezza * 0.55), { x: 0, y: 1 }, lunghezza * 0.06);
    const ramo = quadratica(inizio, controllo, fine);
    rami.push({
      linea: `M ${punto(inizio)} Q ${punto(controllo)} ${punto(fine)}`,
      aghi: aghi(ramo, 0.12, 1, Math.round(lunghezza / 13), 25 - t * 6, caso),
    });

    // Sui rami lunghi un rametto secondario verso l'esterno
    if (lunghezza > 105) {
      const u = 0.45 + caso() * 0.15;
      const attacco = ramo(u);
      const dir = ruota(direzione(ramo, u), lato * (0.55 + caso() * 0.3));
      const l = lunghezza * (0.32 + caso() * 0.12);
      const punta = { x: attacco.x + dir.x * l, y: attacco.y + dir.y * l - l * 0.15 };
      const ctrl = somma(attacco, dir, l * 0.5);
      const rametto = quadratica(attacco, ctrl, punta);
      rami.push({
        linea: `M ${punto(attacco)} Q ${punto(ctrl)} ${punto(punta)}`,
        aghi: aghi(rametto, 0.15, 1, Math.max(3, Math.round(l / 13)), 21 - t * 5, caso),
      });
    }

    // Bacche sotto alcuni rami, a grappoli di due o tre
    if (i === 1 || i === 2 || i === 4) {
      const u = 0.32 + caso() * 0.1;
      const base = ramo(u);
      const giu = normalizza(somma(normale(direzione(ramo, u)), { x: 0, y: 0.6 }, lato));
      const r = 11 + caso() * 2;
      const prima = somma(base, giu, r + 5);
      bacche.push(bacca(prima, r));
      bacche.push(bacca(somma(somma(prima, direzione(ramo, u), r * 1.75), giu, r * 0.5), r * 0.92));
      if (i !== 4) {
        bacche.push(bacca(somma(somma(prima, direzione(ramo, u), -r * 1.2), giu, r * 1.45), r * 0.86));
      }
    }
  });

  return { larghezza: 420, altezza: 760, rami, bacche };
}

// ------------------------------------------------------------------ lavanda

export interface DisegnoLavanda {
  larghezza: number;
  altezza: number;
  steli: string[];
  foglie: string[];
  fiori: string[];
}

/** Tre spighe di lavanda (Lavandula angustifolia) con le foglie alla base. viewBox 0 0 300 760. */
export function lavanda(seme = 5): DisegnoLavanda {
  const caso = casuale(seme);
  const base = { x: 152, y: 752 };
  const punte = [
    { x: 88, y: 92 },
    { x: 162, y: 30 },
    { x: 228, y: 128 },
  ];
  const steli: string[] = [];
  const foglie: string[] = [];
  const fiori: string[] = [];

  punte.forEach((cima, s) => {
    const partenza = { x: base.x + (s - 1) * 6, y: base.y };
    const meta = { x: (partenza.x + cima.x) / 2 + (s - 1) * 22 + 10, y: (partenza.y + cima.y) / 2 + 20 };
    const stelo = quadratica(partenza, meta, cima);
    steli.push(`M ${punto(partenza)} Q ${punto(meta)} ${punto(cima)}`);

    // La spiga: verticilli di fiori, radi in basso e fitti verso la punta
    const inizioSpiga = 0.6 + caso() * 0.04;
    const verticilli = 19;
    const fiore = (p: P, d: P, l: number) => {
      fiori.push(`${mandorla(p, d, l, l * 0.3, 0.3)} ${cerchio(somma(p, d, l * 1.04), l * 0.19)}`);
    };
    for (let k = 0; k <= verticilli; k++) {
      const x = k / verticilli;
      const u = inizioSpiga + (1 - inizioSpiga) * x ** 0.7;
      const p = stelo(u);
      const d = direzione(stelo, u);
      const grandezza = 24 * (1 - 0.42 * x);
      for (const angolo of [-0.78, -0.36, 0.36, 0.78]) {
        if (k < 2 && Math.abs(angolo) < 0.5) continue; // i primi verticilli sono più radi
        fiore(p, ruota(d, angolo + (caso() - 0.5) * 0.22), grandezza * (0.72 + caso() * 0.3));
      }
      if (k % 2 === 1) {
        fiore(p, ruota(d, (caso() - 0.5) * 0.2), grandezza * 0.8);
      }
    }
  });

  // Foglie lineari che partono dal piede
  const angoli = [-0.7, -0.5, -0.32, -0.14, 0.06, 0.24, 0.42, 0.62];
  angoli.forEach((angolo, i) => {
    const d = ruota({ x: 0, y: -1 }, angolo + (caso() - 0.5) * 0.08);
    const lunghezza = 150 + caso() * 90 - Math.abs(angolo) * 50;
    const piede = { x: base.x + (i - 3.5) * 2.5, y: base.y - 4 - caso() * 10 };
    foglie.push(mandorla(piede, d, lunghezza, 4 + caso() * 1.4, 0.04));
  });

  return { larghezza: 300, altezza: 760, steli, foglie, fiori };
}

// ------------------------------------------------------------------ rametti piccoli (per le schede)

export interface Rametto {
  larghezza: number;
  altezza: number;
  /** Tratti senza riempimento. */
  linee: string[];
  /** Forme con il riempimento (bacche o fiori). */
  pieni: string[];
}

/** Un solo rametto, leggibile anche a 60 px di altezza. viewBox 0 0 64 100. */
export function rametto(tipo: 'ginepro' | 'lavanda', seme = 3): Rametto {
  const caso = casuale(seme);
  if (tipo === 'ginepro') {
    const fusto = quadratica({ x: 30, y: 97 }, { x: 22, y: 50 }, { x: 36, y: 6 });
    const rametto = quadratica({ x: 27.5, y: 62 }, { x: 40, y: 50 }, { x: 54, y: 36 });
    return {
      larghezza: 64,
      altezza: 100,
      linee: [
        'M 30 97 Q 22 50 36 6',
        'M 27.5 62 Q 40 50 54 36',
        aghi(fusto, 0.22, 1, 10, 13, caso),
        aghi(rametto, 0.2, 1, 4, 10, caso),
      ],
      pieni: [cerchio({ x: 20, y: 70 }, 4.6), cerchio({ x: 14, y: 77 }, 4.2)],
    };
  }
  const stelo = quadratica({ x: 33, y: 97 }, { x: 28, y: 55 }, { x: 31, y: 6 });
  const pieni: string[] = [];
  for (let k = 0; k <= 9; k++) {
    const x = k / 9;
    const u = 0.42 + 0.58 * x ** 0.75;
    const p = stelo(u);
    const d = direzione(stelo, u);
    const l = 9.5 * (1 - 0.4 * x);
    for (const angolo of [-0.7, 0.7]) {
      const dir = ruota(d, angolo + (caso() - 0.5) * 0.2);
      pieni.push(`${mandorla(p, dir, l, l * 0.32, 0.3)} ${cerchio(somma(p, dir, l * 1.05), l * 0.2)}`);
    }
  }
  return {
    larghezza: 64,
    altezza: 100,
    linee: [
      'M 33 97 Q 28 55 31 6',
      mandorla({ x: 33, y: 95 }, normalizza({ x: -0.45, y: -1 }), 34, 2.4, 0.05),
      mandorla({ x: 33, y: 95 }, normalizza({ x: 0.5, y: -1 }), 30, 2.4, 0.05),
    ],
    pieni,
  };
}

// ------------------------------------------------------------------ genziana e pino mugo (per la striscia delle botaniche)

export interface DisegnoTratti {
  larghezza: number;
  altezza: number;
  /** Tratti senza riempimento. */
  linee: string[];
  /** Forme con il riempimento: fiori, gemme, pigna. */
  pieni: string[];
}

/** Una foglia con le nervature parallele, come quelle della genziana maggiore. */
function fogliaNervata(a: P, d: P, l: number, w: number): { contorno: string; nervature: string } {
  const n = normale(d);
  const cima = somma(a, d, l);
  const nervature = [-0.5, 0, 0.5]
    .map((k) => {
      const controllo = somma(somma(a, d, l * 0.5), n, w * k * 1.1);
      return `M ${punto(somma(a, d, l * 0.06))} Q ${punto(controllo)} ${punto(somma(cima, d, -l * 0.08))}`;
    })
    .join(' ');
  return { contorno: mandorla(a, d, l, w, 0.12), nervature };
}

/** Un fiore a stella di cinque petali stretti, girato di «giro» radianti. */
function stella(c: P, r: number, giro: number): string {
  return [0, 1, 2, 3, 4]
    .map((k) => mandorla(c, ruota({ x: 0, y: -1 }, giro + (k * 2 * Math.PI) / 5), r, r * 0.2, 0.2))
    .join(' ');
}

/**
 * Genziana maggiore (Gentiana lutea): foglie larghe e nervate a coppie, fiori a
 * stella in verticilli lungo lo stelo. viewBox 0 0 260 760.
 */
export function genziana(seme = 7): DisegnoTratti {
  const caso = casuale(seme);
  const p0 = { x: 132, y: 752 };
  const p1 = { x: 122, y: 560 };
  const p2 = { x: 140, y: 300 };
  const p3 = { x: 128, y: 34 };
  const stelo = cubica(p0, p1, p2, p3);
  const linee: string[] = [`M ${punto(p0)} C ${punto(p1)} ${punto(p2)} ${punto(p3)}`];
  const pieni: string[] = [];

  // Tre coppie di foglie, più piccole salendo
  [
    { u: 0.07, l: 168 },
    { u: 0.24, l: 136 },
    { u: 0.4, l: 102 },
  ].forEach(({ u, l }) => {
    const p = stelo(u);
    const su = direzione(stelo, u);
    for (const lato of [-1, 1]) {
      const d = ruota(su, lato * (0.95 + caso() * 0.15));
      const foglia = fogliaNervata(p, d, l * (0.92 + caso() * 0.12), l * 0.27);
      linee.push(foglia.contorno, foglia.nervature);
    }
  });

  // Verticilli di fiori: ognuno con due brattee e quattro o cinque stelle sui piccioli
  [0.53, 0.65, 0.76, 0.86, 0.95].forEach((u, i) => {
    const p = stelo(u);
    const su = direzione(stelo, u);
    const scala = 1 - i * 0.1;
    for (const lato of [-1, 1]) {
      linee.push(mandorla(p, ruota(su, lato * 1.15), 40 * scala, 9 * scala, 0.15));
    }
    const quanti = i === 4 ? 3 : 5;
    for (let k = 0; k < quanti; k++) {
      const angolo = -1.25 + (2.5 * k) / (quanti - 1) + (caso() - 0.5) * 0.15;
      const d = ruota(su, angolo);
      const lunghezza = (24 + caso() * 12) * scala;
      const fiore = somma(p, d, lunghezza);
      linee.push(`M ${punto(p)} L ${punto(fiore)}`);
      pieni.push(stella(fiore, (15 + caso() * 3) * scala, caso() * 1.2));
    }
  });

  return { larghezza: 260, altezza: 760, linee, pieni };
}

/** Aghi di pino a ciuffi: lunghi, rigidi, rivolti in avanti e appena incurvati. */
function ciuffi(curva: Curva, da: number, a: number, quanti: number, lunghezza: number, caso: () => number): string {
  const tratti: string[] = [];
  for (let k = 0; k <= quanti; k++) {
    const u = da + ((a - da) * k) / quanti;
    const p = curva(u);
    const d = direzione(curva, u);
    const verso = (u - da) / (a - da);
    for (const lato of [-1, 1]) {
      for (const apertura of [0.38, 0.62]) {
        const angolo = lato * (apertura + caso() * 0.18) * (1 - 0.35 * verso);
        const l = lunghezza * (0.8 + caso() * 0.35) * (1 - 0.3 * verso * verso);
        const dir = ruota(d, angolo);
        const fine = somma(p, dir, l);
        const curvatura = somma(somma(p, dir, l * 0.55), normale(dir), lato * l * 0.06);
        tratti.push(`M ${punto(p)} Q ${punto(curvatura)} ${punto(fine)}`);
      }
    }
  }
  return tratti.join(' ');
}

/**
 * Pino mugo (Pinus mugo): un ramo con due getti laterali, aghi a ciuffi, le gemme
 * in punta e una pigna. viewBox 0 0 320 760.
 */
export function pinoMugo(seme = 13): DisegnoTratti {
  const caso = casuale(seme);
  const p0 = { x: 196, y: 752 };
  const p1 = { x: 236, y: 560 };
  const p2 = { x: 108, y: 330 };
  const p3 = { x: 160, y: 70 };
  const fusto = cubica(p0, p1, p2, p3);
  const linee: string[] = [`M ${punto(p0)} C ${punto(p1)} ${punto(p2)} ${punto(p3)}`];
  const pieni: string[] = [];

  // La corteccia in basso: qualche segno corto di traverso
  for (let k = 0; k < 7; k++) {
    const u = 0.04 + k * 0.035;
    const p = fusto(u);
    const n = normale(direzione(fusto, u));
    linee.push(`M ${punto(somma(p, n, -5))} L ${punto(somma(p, n, 5))}`);
  }

  const getti = [
    { inizio: fusto(0.46), fine: { x: 272, y: 236 }, verso: 1 },
    { inizio: fusto(0.6), fine: { x: 46, y: 214 }, verso: -1 },
  ];
  const punte: { p: P; d: P }[] = [{ p: p3, d: direzione(fusto, 1) }];
  linee.push(ciuffi(fusto, 0.36, 0.97, 22, 74, caso));
  for (const getto of getti) {
    const controllo = {
      x: (getto.inizio.x + getto.fine.x) / 2 - getto.verso * 6,
      y: (getto.inizio.y + getto.fine.y) / 2 + 34,
    };
    const ramo = quadratica(getto.inizio, controllo, getto.fine);
    linee.push(`M ${punto(getto.inizio)} Q ${punto(controllo)} ${punto(getto.fine)}`);
    linee.push(ciuffi(ramo, 0.22, 0.96, 13, 64, caso));
    punte.push({ p: getto.fine, d: direzione(ramo, 1) });
  }

  // Le gemme in punta, allungate come candele
  for (const { p, d } of punte) {
    pieni.push(mandorla(p, d, 34, 6.5, 0.3));
  }

  // La pigna, appesa sotto il primo getto
  const attacco = fusto(0.33);
  const giu = normalizza({ x: 0.55, y: 1 });
  const pigna = somma(attacco, giu, 8);
  const lunghezza = 78;
  const larghezza = 25;
  linee.push(`M ${punto(attacco)} L ${punto(pigna)}`);
  pieni.push(mandorla(pigna, giu, lunghezza, larghezza, 0.3));
  const n = normale(giu);
  for (let k = 1; k <= 5; k++) {
    const t = k / 6;
    const centro = somma(pigna, giu, lunghezza * t);
    const w = larghezza * Math.sin(Math.PI * Math.min(0.92, t + 0.08)) * 0.95;
    const da = somma(centro, n, -w);
    const a = somma(centro, n, w);
    const curva = somma(centro, giu, lunghezza * 0.09);
    linee.push(`M ${punto(da)} Q ${punto(curva)} ${punto(a)}`);
  }

  return { larghezza: 320, altezza: 760, linee, pieni };
}

// ------------------------------------------------------------------ crinali (sagome delle montagne)

export interface OpzioniCrinale {
  larghezza?: number;
  altezza?: number;
  /** Quota media delle cime, in frazione dell'altezza (1 = in cima). */
  base?: number;
  /** Quanto cambiano le cime una dall'altra, in frazione dell'altezza. */
  ampiezza?: number;
  /** Quante montagne ci sono nella catena. */
  picchi?: number;
  /**
   * Ripidezza dei versanti: quanta quota si perde per ogni frazione di larghezza.
   * L'SVG si stira in orizzontale, quindi più è largo e basso più serve ripido.
   */
  pendenza?: number;
  /** Il frastaglio delle creste, in frazione dell'altezza. */
  asperita?: number;
  /** Cime marcate: posizione (0–1), altezza in più (frazione) e larghezza (0–1). */
  cime?: { x: number; h: number; w: number }[];
  /** Quota minima: il crinale non scende sotto questa frazione. */
  minimo?: number;
}

/**
 * La sagoma di una catena di montagne, chiusa in basso, per un SVG con il viewBox
 * «0 0 larghezza altezza». Ogni montagna è una piramide con due versanti di
 * ripidezza diversa; la sagoma è, punto per punto, la più alta. Sopra, un po' di
 * frastaglio (spostamento del punto medio). Il caso è seminato: sempre uguale.
 */
export function crinale(seme: number, opzioni: OpzioniCrinale = {}): string {
  const {
    larghezza = 1440,
    altezza = 200,
    base = 0.5,
    ampiezza = 0.4,
    picchi = 6,
    pendenza = 4,
    asperita = 0.05,
    cime = [],
    minimo = 0.04,
  } = opzioni;
  const caso = casuale(seme);

  // Le montagne, sparse lungo la larghezza (anche un po' fuori dai bordi)
  const montagne = Array.from({ length: picchi }, (_, i) => ({
    x: (i - 0.1 + caso() * 1.2) / (picchi - 1 || 1),
    h: base + (caso() - 0.5) * ampiezza,
    sinistra: pendenza * (0.6 + caso() * 0.8),
    destra: pendenza * (0.6 + caso() * 0.8),
  }));

  // Il frastaglio: spostamento del punto medio, attorno allo zero
  const n = 2 ** 8;
  const rumore = new Array<number>(n + 1).fill(0);
  rumore[0] = (caso() - 0.5) * asperita;
  rumore[n] = (caso() - 0.5) * asperita;
  let passo = n;
  let escursione = asperita * 2;
  while (passo > 1) {
    const meta = passo / 2;
    for (let i = meta; i < n; i += passo) {
      rumore[i] = (rumore[i - meta] + rumore[i + meta]) / 2 + (caso() - 0.5) * escursione;
    }
    passo = meta;
    escursione *= 0.55;
  }

  const punti = rumore.map((r, i) => {
    const x = i / n;
    const sagoma = montagne.reduce(
      (alta, mt) => Math.max(alta, mt.h - Math.abs(x - mt.x) * (x < mt.x ? mt.sinistra : mt.destra)),
      -1,
    );
    const rilievo = cime.reduce((somma, c) => somma + c.h * Math.exp(-(((x - c.x) / c.w) ** 2)), 0);
    const quota = Math.min(0.99, Math.max(minimo, sagoma + rilievo + r));
    return `${arrotonda(x * larghezza)} ${arrotonda(altezza * (1 - quota))}`;
  });
  return `M 0 ${altezza} L ${punti.join(' L ')} L ${larghezza} ${altezza} Z`;
}
