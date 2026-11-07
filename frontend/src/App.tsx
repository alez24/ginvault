import { Outlet, Route, Routes } from 'react-router';

import { Layout } from './componenti/Layout';
import { Protetta } from './componenti/Protetta';
import { PAGINE } from './navigazione';
import { Accedi } from './pagine/Accedi';
import { AreaSoci } from './pagine/AreaSoci';
import { DettaglioEdizione } from './pagine/distilleria/DettaglioEdizione';
import { DettaglioFormulazione } from './pagine/distilleria/DettaglioFormulazione';
import { ElencoEdizioni } from './pagine/distilleria/ElencoEdizioni';
import { ElencoFormulazioni } from './pagine/distilleria/ElencoFormulazioni';
import { NuovaFormulazione } from './pagine/distilleria/NuovaFormulazione';
import { Home } from './pagine/Home';
import { NonTrovata } from './pagine/NonTrovata';
import { Profilo } from './pagine/Profilo';

/** Le route. Chi può aprire cosa è deciso in navigazione.ts (PAGINE). */
export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="/accedi" element={<Accedi />} />
        <Route
          path={PAGINE.profilo.percorso}
          element={
            <Protetta pagina={PAGINE.profilo}>
              <Profilo />
            </Protetta>
          }
        />

        {/* Back-office del distillatore (storia di Marco) */}
        <Route
          path={PAGINE.formulazioni.percorso}
          element={
            <Protetta pagina={PAGINE.formulazioni}>
              <Outlet />
            </Protetta>
          }
        >
          <Route index element={<ElencoFormulazioni />} />
          <Route path="nuova" element={<NuovaFormulazione />} />
          <Route path=":id" element={<DettaglioFormulazione />} />
        </Route>
        <Route
          path={PAGINE.edizioni.percorso}
          element={
            <Protetta pagina={PAGINE.edizioni}>
              <Outlet />
            </Protetta>
          }
        >
          <Route index element={<ElencoEdizioni />} />
          <Route path=":id" element={<DettaglioEdizione />} />
        </Route>

        <Route
          path={PAGINE.soci.percorso}
          element={
            <Protetta pagina={PAGINE.soci}>
              <AreaSoci />
            </Protetta>
          }
        />
        <Route path="*" element={<NonTrovata />} />
      </Route>
    </Routes>
  );
}
