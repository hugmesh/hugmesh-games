// Federation config for the login remote-shell.
//
// `name`     — federation_modules.slug for the login remote.
// `exposes`  — './LoginComponent' is the host's loadRemoteModule key.
//              The host's routing config dynamic-imports it under /login.
// `shared`   — host + remote both pull @angular/* via shareAll so the
//              auth flow (interceptors, RouterModule state, FormsModule)
//              can move data across the federation seam without two
//              competing copies of Angular's runtime.

const { withNativeFederation, shareAll } = require('@angular-architects/native-federation/config');

module.exports = withNativeFederation({
  name: 'login',

  exposes: {
    './LoginComponent': './projects/login/src/app/login-remote.component.ts',
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
