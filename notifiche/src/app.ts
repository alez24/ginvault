import type { INestApplication, NestApplicationOptions } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { WsAdapter } from '@nestjs/platform-ws';
import { AppModule } from './app.module';
import type { Configurazione } from './configurazione';

/**
 * Crea l'app (senza avviarla): la usano main.ts e i test.
 * WsAdapter = WebSocket "puri" (libreria ws), quelli del browser, invece di
 * Socket.IO che è il default di Nest e vuole un client suo.
 */
export async function creaApp(
  configurazione: Configurazione,
  opzioni?: NestApplicationOptions,
): Promise<INestApplication> {
  const app = await NestFactory.create(AppModule.con(configurazione), opzioni);
  app.useWebSocketAdapter(new WsAdapter(app));
  return app;
}
