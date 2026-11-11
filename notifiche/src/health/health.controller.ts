import { Controller, Get, ServiceUnavailableException } from '@nestjs/common';
import { Pubblico } from '../auth/decoratori';
import { DatabaseService } from '../database/database.service';
import { ConsumerEventi } from '../eventi/consumer-eventi.service';

// GET /health -> {"status":"UP"} come l'Actuator di Spring, così Docker e
// Kubernetes controllano tutti i servizi allo stesso modo. 503 con
// {"status":"DOWN"} se il database non risponde.
// Kafka non cambia lo stato: senza Kafka REST e WebSocket funzionano, e il
// consumer si ricollega da solo. Il campo "kafka" dice solo come sta.
// @Pubblico(): lo usa l'healthcheck di Docker, che non ha un JWT.
@Controller('health')
export class HealthController {
  constructor(
    private readonly db: DatabaseService,
    private readonly consumer: ConsumerEventi,
  ) {}

  @Pubblico()
  @Get()
  async check() {
    const kafka = this.consumer.statoKafka();
    try {
      await this.db.query('SELECT 1');
    } catch {
      throw new ServiceUnavailableException({ status: 'DOWN', database: 'DOWN', kafka });
    }
    return { status: 'UP', database: 'UP', kafka };
  }
}
