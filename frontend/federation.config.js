// Host-side federation config. The Angular shell consumes remote modules
// (login, admin, per-game) at runtime, so it has no `exposes` block —
// only a list of shared deps that must NOT duplicate between host and
// remote bundles (Angular itself, RxJS, zone.js, etc.).
//
// shareAll(...) auto-shares everything in package.json's `dependencies`;
// `skip` carves out RxJS subpaths the bundler can't resolve as ESM
// (default native-federation guidance).

const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'snapdragon-host',

  shared: {
    ...shareAll({ singleton: true, strictVersion: true, requiredVersion: 'auto' }),
  },

  skip: [
    'rxjs/ajax',
    'rxjs/fetch',
    'rxjs/testing',
    'rxjs/webSocket',
    // Avoid pulling Angular's secondary entry points twice; native-fed
    // covers the primary entries in shareAll already.
    '@angular/material/prebuilt-themes',
  ],
});
