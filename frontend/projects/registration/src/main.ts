// Build entry for the registration remote-shell. Native-federation
// loads this `main.js` as part of remote initialization — top-level
// side effects would run inside the host shell, so keep this file
// side-effect-free. The host fetches the exposed component via
//   loadRemoteModule({remoteName:'registration', exposedModule:'./RegistrationComponent'})
// per federation.config.js, which routes directly to
// RegistrationComponent-*.js.
export { RegistrationRemoteComponent } from './app/registration-remote.component';
