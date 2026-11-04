import { Controller, Get } from '@nestjs/common';

// GET /health -> stesso formato dell'Actuator di Spring ({"status":"UP"}),
// così gateway, Docker e (in futuro) Kubernetes controllano tutti i servizi allo stesso modo.
@Controller('health')
export class HealthController {
  @Get()
  check() {
    return { status: 'UP' };
  }
}
