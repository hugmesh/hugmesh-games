// Build entry for the login remote-shell. Native-federation loads this
// `main.js` as part of remote initialization — top-level side effects
// would run inside the host shell, so keep this file side-effect-free.
// The host fetches the exposed component via
//   loadRemoteModule({remoteName:'login', exposedModule:'./LoginComponent'})
// per federation.config.js, which routes directly to LoginComponent-*.js.
export { LoginRemoteComponent } from './app/login-remote.component';
