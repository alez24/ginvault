import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  // Chiude l'app in modo pulito quando Docker invia SIGTERM (docker stop)
  app.enableShutdownHooks();
  // Porta fissa per servizio (8086 = notifiche), sovrascrivibile con PORT
  await app.listen(process.env.PORT ?? 8086);
}
bootstrap();
