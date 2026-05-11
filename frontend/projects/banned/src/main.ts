// Build entry for the banned remote-shell. Native-federation loads
// this `main.js` as part of remote initialization — top-level side
// effects would run inside the host shell, so keep this file
// side-effect-free. The host fetches the exposed component via
//   loadRemoteModule({remoteName:'banned', exposedModule:'./BannedComponent'})
// per federation.config.js, which routes directly to
// BannedComponent-*.js.
export { BannedRemoteComponent } from './app/banned-remote.component';
