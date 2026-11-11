/**
 * Notifiche con PostgreSQL e Kafka veri (Testcontainers) e due istanze nello
 * stesso processo: A legge Kafka, B no (KAFKA_ATTIVO=false). Un utente
 * collegato a B riceve lo stesso gli avvisi elaborati da A: è il fan-out tra
 * repliche con LISTEN/NOTIFY.
 *
 * Ogni test usa edizioni, prenotazioni e utenti suoi (UUID nuovi), così i test
 * non si disturbano anche se il database e i topic sono gli stessi.
 */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { after, before, describe, it } from 'node:test';
import { Kafka, logLevel, type Consumer, type KafkaMessage } from 'kafkajs';
import {
  type Ambiente,
  avviaAmbiente,
  avviaNotifiche,
  busta,
  ClientAvvisi,
  finche,
  type Istanza,
  pubblica,
  tokenFalso,
} from '../supporto/ambiente';

const spazi = (testo: string) => testo.replace(/ /g, ' ');

let ambiente: Ambiente;
let a: Istanza;
let b: Istanza;
let lettoreDlt: Consumer;
const scartati: { partizione: number; messaggio: KafkaMessage }[] = [];
const client: ClientAvvisi[] = [];

function utente(): string {
  return `github:${randomUUID().slice(0, 8)}`;
}

async function collega(istanza: Istanza, sub: string): Promise<ClientAvvisi> {
  const avvisi = ClientAvvisi.apri(istanza.ws, await ambiente.gateway.token(sub));
  client.push(avvisi);
  await avvisi.aspetta((m) => m.tipo === 'connesso');
  return avvisi;
}

async function rest(
  istanza: Istanza,
  metodo: string,
  percorso: string,
  sub?: string,
): Promise<{ stato: number; corpo: any }> {
  const headers: Record<string, string> = {};
  if (sub) {
    headers.Authorization = `Bearer ${await ambiente.gateway.token(sub)}`;
  }
  const risposta = await fetch(istanza.http + percorso, { method: metodo, headers });
  const testo = await risposta.text();
  return { stato: risposta.status, corpo: testo ? JSON.parse(testo) : undefined };
}

async function notificheDi(sub: string, quante: number) {
  return finche(async () => {
    const { corpo } = await rest(a, 'GET', '/notifiche', sub);
    return corpo.notifiche.length >= quante ? corpo : undefined;
  });
}

function edizioneCreata(edizioneId: string, botanica = 'lavanda') {
  return busta('EdizioneCreata', {
    edizioneId,
    formulazione: { id: randomUUID(), versione: 1 },
    botanicaDiStagione: botanica,
    grammiPerBottiglia: 20.0,
    origineBotanica: 'Alta Val Varaita (Alpi Cozie)',
    kgPrevisti: 12.0,
    tiratura: 600,
    quote: { SOCI: 300, BAR: 150, ONLINE: 150 },
  });
}

function prenotazioneConfermata(
  edizioneId: string,
  acquirente: string,
  numeri: number[],
  prenotazioneId = randomUUID(),
  canale = 'SOCI',
) {
  return busta('PrenotazioneConfermata', {
    prenotazioneId,
    edizioneId,
    acquirente,
    canale,
    bottiglie: numeri.length,
    numeriBottiglia: numeri,
    totale: 45.0 * numeri.length,
    pagamentoId: randomUUID(),
  });
}

describe('Notifiche con PostgreSQL e Kafka', { timeout: 300_000 }, () => {
  before(async () => {
    ambiente = await avviaAmbiente();
    a = await avviaNotifiche(ambiente, true);
    b = await avviaNotifiche(ambiente, false);
    lettoreDlt = new Kafka({
      clientId: 'test-dlt',
      brokers: [ambiente.bootstrap],
      logLevel: logLevel.NOTHING,
    }).consumer({ groupId: `test-dlt-${randomUUID()}` });
    await lettoreDlt.connect();
    await lettoreDlt.subscribe({ topic: 'notifiche.dlt', fromBeginning: true });
    await lettoreDlt.run({
      eachMessage: async ({ partition, message }) => {
        scartati.push({ partizione: partition, messaggio: message });
      },
    });
  });

  after(async () => {
    client.forEach((c) => c.chiudi());
    await lettoreDlt?.disconnect();
    await a?.app.close();
    await b?.app.close();
    await ambiente?.chiudi();
  });

  it('Giulia ha la 112 e la 113; la tiratura scende e lo sa solo chi ha prenotato', async () => {
    const edizione = randomUUID();
    const giulia = utente();
    const anna = utente();
    const marco = utente();
    const avvisiGiulia = await collega(a, giulia);
    const avvisiMarco = await collega(a, marco);
    assert.equal(avvisiGiulia.messaggi[0].nonLette, 0);

    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, edizioneCreata(edizione));
    const prenotazione = randomUUID();
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      prenotazioneConfermata(edizione, giulia, [112, 113], prenotazione),
    );
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      prenotazioneConfermata(edizione, anna, [7]),
    );

    const conferma = await avvisiGiulia.aspettaNotifica('PRENOTAZIONE_CONFERMATA');
    assert.equal(conferma.titolo, 'Prenotazione confermata');
    assert.equal(
      spazi(conferma.testo),
      "Pagamento riuscito: 90,00 € per 2 bottiglie dell'edizione con lavanda. " +
        'Le tue bottiglie sono la 112 e la 113.',
    );
    assert.equal(conferma.prenotazioneId, prenotazione);
    assert.equal(conferma.edizioneId, edizione);

    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      busta('TiraturaRidotta', {
        edizioneId: edizione,
        tiraturaPrima: 600,
        tiratura: 540,
        quote: { SOCI: 300, BAR: 150, ONLINE: 90 },
        kgDisponibili: 10.8,
        causa: 'consegna ridotta: 10,8 su 12 kg',
        resaRicalcolataId: randomUUID(),
      }),
    );

    const tiratura = await avvisiGiulia.aspettaNotifica('TIRATURA_RIDOTTA');
    assert.equal(tiratura.titolo, 'La tiratura scende a 540 bottiglie');
    assert.equal(
      tiratura.testo,
      "L'edizione con lavanda passa da 600 a 540 bottiglie (consegna ridotta: 10,8 su 12 kg). " +
        'Le tue bottiglie, la 112 e la 113, restano tue.',
    );
    assert.deepEqual(tiratura.dati.numeriBottiglia, [112, 113]);

    // Anna ha la sua notifica, Marco (nessuna prenotazione) niente
    const diAnna = await notificheDi(anna, 2);
    assert.equal(diAnna.nonLette, 2);
    assert.match(diAnna.notifiche[0].testo, /La tua bottiglia, la 7, resta tua\.$/);
    await new Promise((risolvi) => setTimeout(risolvi, 500));
    assert.deepEqual(avvisiMarco.notifiche(), []);
    assert.deepEqual((await rest(a, 'GET', '/notifiche', marco)).corpo, {
      nonLette: 0,
      notifiche: [],
    });

    // REST: dalla più recente, tutte e due da leggere
    const { stato, corpo } = await rest(a, 'GET', '/notifiche', giulia);
    assert.equal(stato, 200);
    assert.equal(corpo.nonLette, 2);
    assert.deepEqual(
      corpo.notifiche.map((n: { tipo: string }) => n.tipo),
      ['TIRATURA_RIDOTTA', 'PRENOTAZIONE_CONFERMATA'],
    );
  });

  it("fan-out: l'avviso elaborato da A arriva a chi è collegato a B", async () => {
    const edizione = randomUUID();
    const giulia = utente();
    const suB = await collega(b, giulia);
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, edizioneCreata(edizione, 'genziana'));
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      prenotazioneConfermata(edizione, giulia, [1, 2]),
    );
    const notifica = await suB.aspettaNotifica('PRENOTAZIONE_CONFERMATA');
    assert.match(notifica.testo, /dell'edizione con genziana\. Le tue bottiglie sono la 1 e la 2\.$/);
  });

  it('lo stesso evento due volte: una notifica sola; i tipi che non servono si ignorano', async () => {
    const edizione = randomUUID();
    const giulia = utente();
    const conferma = prenotazioneConfermata(edizione, giulia, [3]);
    const rifiutato = busta('RicalcoloRifiutato', { edizioneId: edizione, motivo: 'PRENOTATE' });
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, conferma);
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, conferma);
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, rifiutato);
    // l'ultimo della stessa partizione: quando c'è lui, gli altri sono già passati
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      prenotazioneConfermata(edizione, giulia, [4]),
    );
    const { notifiche } = await notificheDi(giulia, 2);
    await new Promise((risolvi) => setTimeout(risolvi, 300));
    assert.equal((await rest(a, 'GET', '/notifiche', giulia)).corpo.notifiche.length, 2);
    assert.equal(notifiche.length, 2);

    const righe = await ambiente.db.query(
      'SELECT event_id, avvisi FROM evento_elaborato WHERE event_id = ANY($1)',
      [[conferma.eventId, rifiutato.eventId]],
    );
    assert.deepEqual(righe.rows, [{ event_id: conferma.eventId, avvisi: 1 }]);
  });

  it('prenotazione scaduta e rimborso del pagamento arrivato tardi', async () => {
    const edizione = randomUUID();
    const prenotazione = randomUUID();
    const giulia = utente();
    const avvisi = await collega(a, giulia);
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, edizioneCreata(edizione));
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      busta('PrenotazioneAnnullata', {
        prenotazioneId: prenotazione,
        edizioneId: edizione,
        acquirente: giulia,
        canale: 'SOCI',
        bottiglie: 2,
        causa: 'SCADUTA',
        spiegazione:
          'Il tempo per pagare è finito il 1 agosto 2026 alle 11:20: le bottiglie sono tornate disponibili.',
      }),
    );
    const annullata = await avvisi.aspettaNotifica('PRENOTAZIONE_ANNULLATA');
    assert.equal(annullata.titolo, 'Prenotazione scaduta');
    assert.equal(
      annullata.testo,
      "La prenotazione di 2 bottiglie dell'edizione con lavanda è annullata. " +
        'Il tempo per pagare è finito il 1 agosto 2026 alle 11:20: le bottiglie sono tornate disponibili.',
    );

    await pubblica(
      ambiente.producer,
      'pagamenti.esiti',
      prenotazione,
      busta('RimborsoEseguito', {
        prenotazioneId: prenotazione,
        pagamentoId: randomUUID(),
        edizioneId: edizione,
        acquirente: giulia,
        importoCentesimi: 9000,
        valuta: 'eur',
        riferimentoStripe: 're_test',
        motivo: 'pagamento arrivato a prenotazione scaduta',
      }),
    );
    const rimborso = await avvisi.aspettaNotifica('RIMBORSO_ESEGUITO');
    assert.equal(
      spazi(rimborso.testo),
      "Ti abbiamo restituito 90,00 € per la prenotazione dell'edizione con lavanda " +
        '(pagamento arrivato a prenotazione scaduta).',
    );
  });

  it('dead letter: un messaggio illeggibile subito, un errore del database dopo i tentativi', async () => {
    const edizione = randomUUID();
    const giulia = utente();
    const rotto = await pubblica(ambiente.producer, 'edizioni.eventi', edizione, '{"eventId": ');
    // canale di 20 caratteri: la colonna è varchar(16), il database rifiuta
    const troppoLungo = prenotazioneConfermata(edizione, giulia, [9], randomUUID(), 'X'.repeat(20));
    await pubblica(ambiente.producer, 'edizioni.eventi', edizione, troppoLungo);
    await pubblica(
      ambiente.producer,
      'edizioni.eventi',
      edizione,
      prenotazioneConfermata(edizione, giulia, [10]),
    );

    // la partizione non resta bloccata: il messaggio dopo arriva
    const { notifiche } = await notificheDi(giulia, 1);
    assert.match(notifiche[0].testo, /La tua bottiglia è la 10\.$/);

    const daQuesta = await finche(async () => {
      const trovati = scartati.filter((s) => s.messaggio.key?.toString() === edizione);
      return trovati.length >= 2 ? trovati : undefined;
    });
    const header = (s: (typeof daQuesta)[number], nome: string) => s.messaggio.headers?.[nome]?.toString();
    const [illeggibile, database] = daQuesta;
    assert.equal(illeggibile.partizione, rotto);
    assert.equal(illeggibile.messaggio.value?.toString(), '{"eventId": ');
    assert.equal(header(illeggibile, 'kafka_dlt-original-topic'), 'edizioni.eventi');
    assert.equal(header(illeggibile, 'kafka_dlt-original-partition'), String(rotto));
    assert.equal(header(illeggibile, 'kafka_dlt-exception-fqcn'), 'EventoNonValido');
    assert.equal(header(illeggibile, 'kafka_dlt-exception-message'), 'Il messaggio non è un JSON valido');
    assert.ok(header(illeggibile, 'kafka_dlt-original-offset'));
    assert.equal(header(database, 'kafka_dlt-exception-fqcn'), 'DatabaseError');
    assert.equal(JSON.parse(database.messaggio.value!.toString()).eventId, troppoLungo.eventId);
  });

  it('WebSocket: 4401 senza JWT o con una firma sbagliata, 4001 quando il JWT scade', async () => {
    const senza = ClientAvvisi.apri(a.ws);
    assert.equal((await senza.chiusura).codice, 4401);
    const falso = ClientAvvisi.apri(a.ws, await tokenFalso(utente()));
    assert.equal((await falso.chiusura).codice, 4401);
    const altroIssuer = ClientAvvisi.apri(a.ws, await ambiente.gateway.token(utente(), 300, 'altro'));
    assert.equal((await altroIssuer.chiusura).codice, 4401);

    const breve = ClientAvvisi.apri(a.ws, await ambiente.gateway.token(utente(), 2));
    await breve.aspetta((m) => m.tipo === 'connesso');
    // nel frattempo il keepalive (300 ms nei test)
    await breve.aspetta((m) => m.tipo === 'ping');
    const chiusura = await breve.chiusura;
    assert.equal(chiusura.codice, 4001);
    assert.equal(chiusura.motivo, 'JWT scaduto: ricollegati');
  });

  it('REST: 401 senza JWT, 404 per le notifiche degli altri, letta e tutte lette', async () => {
    assert.equal((await rest(a, 'GET', '/notifiche')).stato, 401);

    const edizione = randomUUID();
    const giulia = utente();
    for (const numero of [20, 21, 22]) {
      await pubblica(
        ambiente.producer,
        'edizioni.eventi',
        edizione,
        prenotazioneConfermata(edizione, giulia, [numero]),
      );
    }
    const { notifiche } = await notificheDi(giulia, 3);
    const id = notifiche[0].id;

    assert.equal((await rest(a, 'POST', `/notifiche/${id}/letta`, utente())).stato, 404);
    assert.equal((await rest(a, 'POST', '/notifiche/non-un-uuid/letta', giulia)).stato, 400);
    assert.equal((await rest(a, 'POST', `/notifiche/${id}/letta`, giulia)).stato, 204);
    const dopo = (await rest(a, 'GET', '/notifiche', giulia)).corpo;
    assert.equal(dopo.nonLette, 2);
    assert.ok(dopo.notifiche.find((n: { id: string }) => n.id === id).lettaIl);

    // chi si collega dopo sa subito quante ne ha da leggere
    const avvisi = await collega(a, giulia);
    assert.equal(avvisi.messaggi[0].nonLette, 2);

    assert.equal((await rest(a, 'POST', '/notifiche/lette', giulia)).stato, 204);
    assert.equal((await rest(a, 'GET', '/notifiche', giulia)).corpo.nonLette, 0);
  });

  it('health: il database risponde; B non legge Kafka', async () => {
    const statoA = await finche(async () => {
      const { corpo } = await rest(a, 'GET', '/health');
      return corpo.kafka === 'IN_ASCOLTO' ? corpo : undefined;
    });
    assert.deepEqual(statoA, { status: 'UP', database: 'UP', kafka: 'IN_ASCOLTO' });
    assert.deepEqual((await rest(b, 'GET', '/health')).corpo, {
      status: 'UP',
      database: 'UP',
      kafka: 'SPENTO',
    });
  });
});
