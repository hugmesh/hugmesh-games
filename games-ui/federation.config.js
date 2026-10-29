// REMOTE bundle config for the Games micro-frontend (native-federation remote
// name "games"). The hugmesh-host-shell consumes the exposed modules via
// loadRemoteModule({ remoteName: 'games', exposedModule: '<key>' }) and mounts
//   './routes'       -> GAMES_ROUTES       at /games        (gamesGuard)
//   './admin-routes' -> GAMES_ADMIN_ROUTES at /games/admin  (gamesAdminGuard)
// so the remote name and the exposed module keys MUST stay unchanged or the
// host's games views break. The manifest key the host uses ("games") must equal
// this `name` — native-federation registers the remote under the name declared
// here, not under the manifest key.
//
// The shared-deps surface + RxJS skip list live in ./federation-base.js so a
// change to the federation contract lands in one file, consistent with the host
// and every other HugMesh remote.
const { hugmeshRemote } = require('./federation-base');

module.exports = hugmeshRemote({
  name: 'games',
  exposes: {
    './routes': './src/app/games.routes.ts',
    './admin-routes': './src/app/games-admin.routes.ts',
  },
});
