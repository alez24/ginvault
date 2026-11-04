import { Controller, Get } from '@nestjs/common';
import { Pubblico } from '../auth/decoratori';

// GET /health -> stesso formato dell'Actuator di Spring ({"status":"UP"}),
// così gateway, Docker e (in futuro) Kubernetes controllano tutti i servizi allo stesso modo.
// @Pubblico(): lo usa l'healthcheck di Docker, che non ha un JWT.
@Controller('health')
export class HealthController {
  @Pubblico()
  @Get()
  check() {
    return { status: 'UP' };
  }
}
