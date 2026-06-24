import { snapdragonRemote } from '../../federation-base.mjs';
// Federation config for the login remote-shell.
//
// `name`     — federation_modules.slug for the login remote.
// `exposes`  — './LoginComponent' is the host's loadRemoteModule key.
//              The host's routing config dynamic-imports it under /login.
// shareAll + skip list comes from federation-base.js so all remotes
// share one surface; specific remotes pass `extraSkip` if needed.



export default snapdragonRemote({
  name: 'login',
  exposes: {
    './LoginComponent': './projects/login/src/app/login-remote.component.ts',
  },
});
