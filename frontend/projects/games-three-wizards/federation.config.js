// Federation config for the Three Wizards game remote (TWiaT).
//
// `name`     — federation_modules.slug = 'games-three-wizards'.
// `exposes`  — './GameRoot' is the loadRemoteModule key the host
//              uses for any remote-game level whose game.slug routes
//              into this bundle (cave + field today).
// shareAll + skip list comes from federation-base.js.
//
// The wasm-fragment that actually streams the Godot bytes is a
// separate concern (see federation_modules row 'wasm-fragment').

const { snapdragonRemote } = require('../../federation-base');

module.exports = snapdragonRemote({
  name: 'games-three-wizards',
  exposes: {
    './GameRoot': './projects/games-three-wizards/src/app/three-wizards-remote.component.ts',
  },
});
