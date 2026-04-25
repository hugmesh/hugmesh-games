// Federation config for the Dodecahedron Demo remote module.
//
// `name`     — what the host's loadRemote() refers to (matches the
//              federation_modules.slug seed).
// `exposes`  — the remote's public surface. The host imports
//              ./GameRoot via loadRemoteModule({remoteName, exposedModule}).
// `shared`   — auto-deduplicate every direct dependency with the host
//              so the remote doesn't ship its own copy of Angular.

const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'games-dodecahedron',

  exposes: {
    './GameRoot': './projects/games-dodecahedron/src/app/dodecahedron-remote.component.ts',
  },

  shared: {
    ...shareAll({ singleton: true, strictVersion: true, requiredVersion: 'auto' }),
  },

  skip: [
    'rxjs/ajax',
    'rxjs/fetch',
    'rxjs/testing',
    'rxjs/webSocket',
  ],
});
