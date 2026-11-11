import { type DynamicModule, Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { JwtAuthGuard } from './auth/jwt-auth.guard';
import { RuoliGuard } from './auth/ruoli.guard';
import { VerificaJwtService } from './auth/verifica-jwt.service';
import { AscoltoNotifiche } from './avvisi/ascolto-notifiche.service';
import { AvvisiGateway } from './avvisi/avvisi.gateway';
import { RegistroConnessioni } from './avvisi/registro-connessioni';
import { CONFIGURAZIONE, type Configurazione } from './configurazione';
import { DatabaseService } from './database/database.service';
import { ConsumerEventi } from './eventi/consumer-eventi.service';
import { ElaborazioneEventi } from './eventi/elaborazione-eventi.service';
import { HealthController } from './health/health.controller';
import { NotificheController } from './notifiche/notifiche.controller';
import { NotificheRepository } from './notifiche/notifiche.repository';

/**
 * Il modulo di Notifiche, con la configurazione passata da fuori
 * (AppModule.con(...)): main.ts la legge dalle variabili d'ambiente, i test ne
 * scrivono una per ogni istanza.
 */
@Module({})
export class AppModule {
  static con(configurazione: Configurazione): DynamicModule {
    return {
      module: AppModule,
      controllers: [HealthController, NotificheController],
      providers: [
        { provide: CONFIGURAZIONE, useValue: configurazione },
        VerificaJwtService,
        DatabaseService,
        NotificheRepository,
        ElaborazioneEventi,
        ConsumerEventi,
        RegistroConnessioni,
        AscoltoNotifiche,
        AvvisiGateway,
        // APP_GUARD = guard applicati a TUTTI gli endpoint HTTP, nell'ordine in
        // cui sono elencati: prima l'autenticazione (401), poi i ruoli (403).
        // Il WebSocket verifica il JWT da sé, all'handshake (AvvisiGateway).
        { provide: APP_GUARD, useClass: JwtAuthGuard },
        { provide: APP_GUARD, useClass: RuoliGuard },
      ],
    };
  }
}
