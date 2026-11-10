import { Navigate, Outlet, Route, Routes } from 'react-router';

import { Layout } from './componenti/Layout';
import { Protetta } from './componenti/Protetta';
import { PAGINE } from './navigazione';
import { Accedi } from './pagine/Accedi';
import { DettaglioOrdine } from './pagine/acquisti/DettaglioOrdine';
import { ElencoOrdini } from './pagine/acquisti/ElencoOrdini';
import { ElencoFornitori, SchedaFornitore } from './pagine/acquisti/Fornitori';
import { DettaglioLotto, ElencoLotti } from './pagine/acquisti/Lotti';
import { NuovoOrdine } from './pagine/acquisti/NuovoOrdine';
import { DettaglioEdizione } from './pagine/distilleria/DettaglioEdizione';
import { DettaglioFormulazione } from './pagine/distilleria/DettaglioFormulazione';
import { DettaglioPiano } from './pagine/distilleria/DettaglioPiano';
import { ElencoEdizioni } from './pagine/distilleria/ElencoEdizioni';
import { ElencoFormulazioni } from './pagine/distilleria/ElencoFormulazioni';
import { ElencoPiani } from './pagine/distilleria/ElencoPiani';
import { NuovaFormulazione } from './pagine/distilleria/NuovaFormulazione';
import { Home } from './pagine/Home';
import { NonTrovata } from './pagine/NonTrovata';
import { Profilo } from './pagine/Profilo';
import { AreaSoci } from './pagine/soci/AreaSoci';
import { EdizioneSoci } from './pagine/soci/EdizioneSoci';

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

        {/* Back-office del distillatore (storie di Marco ed Elena) */}
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
          path={PAGINE.piani.percorso}
          element={
            <Protetta pagina={PAGINE.piani}>
              <Outlet />
            </Protetta>
          }
        >
          <Route index element={<ElencoPiani />} />
          <Route path=":id" element={<DettaglioPiano />} />
        </Route>

        {/* Area acquisti (storia di Elena) */}
        <Route
          path={PAGINE.acquisti.percorso}
          element={
            <Protetta pagina={PAGINE.acquisti}>
              <Outlet />
            </Protetta>
          }
        >
          <Route index element={<ElencoOrdini />} />
          <Route path="ordini" element={<Navigate to={PAGINE.acquisti.percorso} replace />} />
          <Route path="ordini/nuovo" element={<NuovoOrdine />} />
          <Route path="ordini/:id" element={<DettaglioOrdine />} />
          <Route path="lotti" element={<ElencoLotti />} />
          <Route path="lotti/:id" element={<DettaglioLotto />} />
          <Route path="fornitori" element={<ElencoFornitori />} />
          <Route path="fornitori/:id" element={<SchedaFornitore />} />
        </Route>

        {/* Area soci (storia di Giulia) */}
        <Route
          path={PAGINE.soci.percorso}
          element={
            <Protetta pagina={PAGINE.soci}>
              <Outlet />
            </Protetta>
          }
        >
          <Route index element={<AreaSoci />} />
          <Route path="edizioni/:id" element={<EdizioneSoci />} />
        </Route>
        <Route path="*" element={<NonTrovata />} />
      </Route>
    </Routes>
  );
}
