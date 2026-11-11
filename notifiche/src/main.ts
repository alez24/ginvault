import { creaApp } from './app';
import { leggiConfigurazione } from './configurazione';

async function bootstrap() {
  const configurazione = leggiConfigurazione();
  // Livelli di log come INFO dei servizi Spring: niente debug e verbose
  const app = await creaApp(configurazione, { logger: ['fatal', 'error', 'warn', 'log'] });
  // Chiude l'app in modo pulito quando Docker invia SIGTERM (docker stop):
  // WebSocket chiusi con 1001, consumer fuori dal gruppo, pool chiuso
  app.enableShutdownHooks();
  // Porta fissa per servizio (8086 = notifiche), sovrascrivibile con PORT
  await app.listen(configurazione.porta);
}
void bootstrap();
