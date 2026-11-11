import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/*
 * Il dev server di Vite (http://localhost:5173) serve il front-end e inoltra al
 * gateway tutto quello che è suo: API, /auth/me, login e logout. Così il browser
 * vede UNA sola origine, come in produzione (lì il ruolo del proxy lo fa
 * l'Ingress): il cookie SESSION e il cookie XSRF-TOKEN funzionano senza CORS.
 *
 * Gateway predefinito: http://localhost:8080 (Docker Compose, oppure
 * "kubectl port-forward svc/gateway 8080:8080"). Si cambia con GINVAULT_GATEWAY,
 * ad esempio GINVAULT_GATEWAY=http://localhost per passare dall'Ingress di Minikube.
 */
const gateway = process.env.GINVAULT_GATEWAY ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // FRONTEND_URL del gateway punta proprio qui: meglio fermarsi che cambiare porta
    strictPort: true,
    proxy: {
      // API e identità. L'header Host resta localhost:5173, quindi i link costruiti
      // dai servizi (es. Location dopo una POST) puntano al dev server.
      // ws: true inoltra anche il WebSocket degli avvisi (/api/notifiche/avvisi).
      '/api': { target: gateway, ws: true },
      '/auth': { target: gateway },
      '/logout': { target: gateway },
      // Login OAuth2. Qui invece l'Host diventa quello del gateway (changeOrigin):
      // il gateway costruisce il redirect_uri dall'Host, e deve essere quello
      // registrato su Google e GitHub (http://localhost:8080/login/oauth2/code/...).
      '/oauth2': { target: gateway, changeOrigin: true },
      '/login': { target: gateway, changeOrigin: true },
    },
  },
  test: {
    environment: 'node',
  },
});
