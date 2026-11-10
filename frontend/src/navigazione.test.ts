import { describe, expect, it } from 'vitest';

import { accessibile, PAGINE, ruoliRichiesti, vociDelMenu } from './navigazione';
import type { Utente } from './sessione/tipi';

function utente(...ruoli: string[]): Utente {
  return { sub: 'github:42', nome: 'Marco', email: null, ruoli };
}

const etichette = (u: Utente | null) => vociDelMenu(u).map((pagina) => pagina.etichetta);

describe('menu per ruolo', () => {
  it('chi non ha fatto l’accesso vede solo la distilleria', () => {
    expect(etichette(null)).toEqual(['La distilleria']);
  });

  it('il distillatore vede formulazioni, edizioni e produzione, il socio l’area soci', () => {
    expect(etichette(utente('DISTILLATORE'))).toEqual([
      'La distilleria',
      'Formulazioni',
      'Edizioni',
      'Produzione',
      'Il mio profilo',
    ]);
    expect(etichette(utente('SOCIO'))).toEqual(['La distilleria', 'Area soci', 'Il mio profilo']);
  });

  it('con più ruoli le voci si sommano, nell’ordine del menu', () => {
    expect(etichette(utente('SOCIO', 'DISTILLATORE'))).toEqual([
      'La distilleria',
      'Formulazioni',
      'Edizioni',
      'Produzione',
      'Area soci',
      'Il mio profilo',
    ]);
  });

  it('la responsabile acquisti vede l’area acquisti', () => {
    expect(etichette(utente('RESP_ACQUISTI'))).toEqual(['La distilleria', 'Acquisti', 'Il mio profilo']);
  });

  it('un cliente ha solo il profilo', () => {
    expect(etichette(utente('CLIENTE'))).toEqual(['La distilleria', 'Il mio profilo']);
  });
});

describe('pagine protette', () => {
  it('pubblica per tutti, profilo per chiunque abbia fatto l’accesso, il resto per ruolo', () => {
    expect(accessibile(null, PAGINE.home)).toBe(true);
    expect(accessibile(null, PAGINE.profilo)).toBe(false);
    expect(accessibile(utente('CLIENTE'), PAGINE.profilo)).toBe(true);
    expect(accessibile(utente('SOCIO'), PAGINE.formulazioni)).toBe(false);
    expect(accessibile(utente('DISTILLATORE'), PAGINE.formulazioni)).toBe(true);
    expect(accessibile(utente('SOCIO'), PAGINE.edizioni)).toBe(false);
    expect(accessibile(utente('DISTILLATORE'), PAGINE.edizioni)).toBe(true);
    expect(accessibile(utente('DISTILLATORE'), PAGINE.acquisti)).toBe(false);
    expect(accessibile(utente('RESP_ACQUISTI'), PAGINE.acquisti)).toBe(true);
    expect(accessibile(utente('RESP_ACQUISTI'), PAGINE.piani)).toBe(false);
  });

  it('spiega quale ruolo serve', () => {
    expect(ruoliRichiesti(PAGINE.formulazioni)).toBe('Distillatore');
    expect(ruoliRichiesti(PAGINE.soci)).toBe('Socio del club');
  });
});
