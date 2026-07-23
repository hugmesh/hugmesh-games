import { hugmeshRemote } from '../../federation-base.mjs';
// Federation config for the producer-registration remote-shell.
//
// `name`     — federation_modules.slug. Liquibase 031 seeds this so
//              the host shell discovers the remote at bootstrap.
// `exposes`  — './ProducerRegistrationComponent' is the host's
//              loadRemoteModule key; the host's routing config
//              dynamic-imports it under /producer-registration.
//
// Gated to authenticated users — anyone who's logged in can SUBMIT
// a producer-registration form, but the result is `pending review`
// until an admin approves. See decision item #8 in the platform
// roadmap.



export default hugmeshRemote({
  name: 'producer-registration',
  exposes: {
    './ProducerRegistrationComponent':
      './projects/producer-registration/src/app/producer-registration-remote.component.ts',
  },
});
