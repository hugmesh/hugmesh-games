import { hugmeshRemote } from '../../federation-base.mjs';
// Federation config for the registration remote-shell.
//
// `name`     — federation_modules.slug. Liquibase 039 seeds this so
//              the host shell discovers the remote at bootstrap.
// `exposes`  — './RegistrationComponent' is the host's
//              loadRemoteModule key; the host's routing config
//              dynamic-imports it under /registration.
// shareAll + skip list comes from federation-base.js so all remotes
// share one surface.



export default hugmeshRemote({
  name: 'registration',
  exposes: {
    './RegistrationComponent': './projects/registration/src/app/registration-remote.component.ts',
  },
});
