// Federation config for the Three Wizards game remote (TWiaT).
//
// `name`     — federation_modules.slug = 'games-three-wizards'.
// `exposes`  — './GameRoot' is the loadRemoteModule key the host
//              uses for any remote-game level whose game.slug routes
//              into this bundle (cave + field today).
// `shared`   — ride the host's Angular runtime so HUD + control
//              services stay in one DI tree. The wasm-fragment
//              that actually streams the Godot bytes is a separate
//              concern (see federation_modules row 'wasm-fragment').

const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'games-three-wizards',

  exposes: {
    './GameRoot': './projects/games-three-wizards/src/app/three-wizards-remote.component.ts',
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
