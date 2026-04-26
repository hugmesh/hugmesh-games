// Build entry for the federated remote. Native-federation loads this
// `main.js` as part of remote initialization — anything with a top-
// level side effect runs INSIDE the host shell at that point, so a
// `bootstrapApplication(...)` call here would spawn a second Angular
// application inside the host (NG0904 / NG0203). Keep this file
// side-effect-free; the host fetches the exposed component via
// `loadRemoteModule({remoteName, exposedModule:'./GameRoot'})` per
// federation.config.js — that path goes directly to GameRoot-*.js.
//
// The component is re-exported so users opening dist/index.html in
// isolation get a clear "no bootstrap" hint rather than a blank page.
export { DodecahedronRemoteComponent } from './app/dodecahedron-remote.component';
