import { initFederation } from '@angular-architects/native-federation';

// Entry point for the Games native-federation remote. `initFederation()` reads
// the (empty) `assets/federation.manifest.json` and registers this remote's
// exposed modules (`./routes` = GAMES_ROUTES, `./admin-routes` =
// GAMES_ADMIN_ROUTES) with the module-federation runtime so the host shell can
// load them at /games/** and /games/admin/** without bundling them at build
// time. The standalone bootstrap below is only used when this remote is served
// on its own (`ng serve games`, or the screenshot harness).
initFederation()
  .catch((err) => console.error(err))
  .then(() => import('./bootstrap'))
  .catch((err) => console.error(err));
