// Federation config for the admin console remote-shell.
//
// `name`     — federation_modules.slug = 'admin'.
// `exposes`  — './AdminShell' is what the host's loadRemoteModule key
//              resolves to. The admin route in the host loads this
//              behind the ROLE_ADMIN guard.
// `shared`   — admin and host both share Angular runtime so the
//              host's HTTP interceptor (Bearer JWT) survives across
//              the federation seam without two competing copies.

const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'admin',

  exposes: {
    './AdminShell': './projects/admin/src/app/admin-remote.component.ts',
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
