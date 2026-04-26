// Federation config for the Dodecahedron Demo remote module.
//
// `name`     — federation_modules.slug for the dodecahedron remote-game.
// `exposes`  — './GameRoot' is the host's loadRemoteModule key, which
//              maps to the registered <app-dodecahedron-remote> Web
//              Component (createCustomElement, see RemoteLoaderService).
// shareAll + skip list comes from federation-base.js.

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'games-dodecahedron',
  exposes: {
    './GameRoot': './projects/games-dodecahedron/src/app/dodecahedron-remote.component.ts',
  },
});
