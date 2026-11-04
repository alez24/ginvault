import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { JwtAuthGuard } from './auth/jwt-auth.guard';
import { RuoliGuard } from './auth/ruoli.guard';
import { VerificaJwtService } from './auth/verifica-jwt.service';
import { HealthController } from './health/health.controller';
import { ProvaController } from './prova/prova.controller';

@Module({
  controllers: [HealthController, ProvaController],
  providers: [
    VerificaJwtService,
    // APP_GUARD = guard applicati a TUTTI gli endpoint, nell'ordine in cui sono
    // elencati: prima l'autenticazione (401), poi i ruoli (403).
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: RuoliGuard },
  ],
})
export class AppModule {}
